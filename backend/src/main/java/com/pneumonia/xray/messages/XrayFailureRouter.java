package com.pneumonia.xray.messages;

import com.pneumonia.xray.models.XrayStatus;
import com.pneumonia.xray.daos.XrayRequestDAO;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Decides, and carries out, the retry-vs-dead-letter branch on a processing failure. Extracted
 * out of {@link XrayProcessingConsumer} specifically so this branching logic is reachable from
 * a plain JUnit + Mockito test, with no Spring context, no live Postgres, and no live
 * RabbitMQ - the {@link XrayRequestDAO} and {@link RabbitTemplate} collaborators here
 * are the only two things a test needs to mock.
 *
 * <p>Retry counting lives in Postgres on the {@code xray_request} row (DEC-0001 item 14), not
 * in RabbitMQ message headers - the caller re-reads the row's current {@code retryCount}
 * before calling {@link #routeFailure(UUID, int)}, and this class decides purely from that
 * pre-increment value: {@code retryCount &lt; 3} means "one more retry is allowed" (increment,
 * RETRYING, publish to the retry queue); {@code retryCount == 3} means "3 retries already
 * happened, this 4th attempt is the last one" (FAILED, publish to the dead-letter queue). Both
 * publishes use the same {@code XrayProcessingRequested}-shaped payload as the original
 * enqueue, via the default exchange (empty string) so the routing key addresses the named
 * queue directly - and both branches leave the original queue message alone; the caller is
 * responsible for acking it regardless of outcome.
 */
@Component
class XrayFailureRouter {

	static final int MAX_RETRIES = 3;
	static final String RETRY_QUEUE = RabbitTopologyConfig.RETRY_QUEUE;
	static final String DEAD_LETTER_QUEUE = RabbitTopologyConfig.DEAD_LETTER_QUEUE;

	private final XrayRequestDAO repository;
	private final RabbitTemplate rabbitTemplate;

	XrayFailureRouter(XrayRequestDAO repository, RabbitTemplate rabbitTemplate) {
		this.repository = repository;
		this.rabbitTemplate = rabbitTemplate;
	}

	/**
	 * @param xrayRequestId the request that just failed a processing attempt.
	 * @param currentRetryCount the row's {@code retryCount} as read immediately before this
	 *     call, i.e. the count of retries already scheduled prior to this failure.
	 * @return the status the request was transitioned to ({@code RETRYING} or {@code FAILED}).
	 */
	XrayStatus routeFailure(UUID xrayRequestId, int currentRetryCount) {

		Instant now = Instant.now();
		XrayProcessingRequested payload = new XrayProcessingRequested(xrayRequestId);

		if (currentRetryCount < MAX_RETRIES) {

			repository.transitionStatusWithRetryCount(
				xrayRequestId, XrayStatus.RETRYING, currentRetryCount + 1, List.of(XrayStatus.PROCESSING), now);

			rabbitTemplate.convertAndSend("", RETRY_QUEUE, payload);

			return XrayStatus.RETRYING;
		}

		repository.transitionStatusWithRetryCount(
			xrayRequestId, XrayStatus.FAILED, currentRetryCount, List.of(XrayStatus.PROCESSING), now);

		rabbitTemplate.convertAndSend("", DEAD_LETTER_QUEUE, payload);

		return XrayStatus.FAILED;
	}
}
