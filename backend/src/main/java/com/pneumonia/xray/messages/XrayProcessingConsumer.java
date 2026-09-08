package com.pneumonia.xray.messages;

import com.pneumonia.infra.api.StorageService;
import com.pneumonia.xray.dtos.PredictResponse;
import com.pneumonia.xray.dtos.XrayRequestResponse;
import com.pneumonia.xray.models.XrayRequest;
import com.pneumonia.xray.models.XrayStatus;
import com.pneumonia.xray.daos.XrayRequestDAO;
import com.pneumonia.xray.services.MlServiceClient;
import com.pneumonia.xray.services.NotChestXrayException;
import com.pneumonia.xray.services.SseNotifier;
import com.pneumonia.xray.services.XrayRequestService;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.core.io.Resource;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Plain Spring AMQP consumer of {@code xray.processing.queue} - deliberately NOT a Spring
 * Modulith construct. Spring Modulith's event externalization ({@link XrayProcessingRequested})
 * is outbound only; there is no Modulith-specific API for consuming a message back off
 * RabbitMQ, so this is an ordinary {@code @RabbitListener}.
 *
 * <p>Manual acknowledgment (see {@code spring.rabbitmq.listener.simple.acknowledge-mode} in
 * {@code application.yml}, concurrency fixed at 1): every branch below - ownership already
 * lost, success, retry, dead-letter - ends by acking the ORIGINAL message. Only an explicit
 * republish (via {@link XrayFailureRouter}, on the retry/dead-letter branches) ever creates a
 * new message; the original is never nacked/rejected/requeued by this listener.
 */
@Component
class XrayProcessingConsumer {

	private static final Logger log = LoggerFactory.getLogger(XrayProcessingConsumer.class);
	private static final String CORRELATION_ID = "correlationId";

	private final XrayRequestService xrayRequestService;
	private final XrayRequestDAO repository;
	private final StorageService storageService;
	private final MlServiceClient mlServiceClient;
	private final XrayFailureRouter failureRouter;
	private final SseNotifier sseNotifier;

	XrayProcessingConsumer(
			XrayRequestService xrayRequestService,
			XrayRequestDAO repository,
			StorageService storageService,
			MlServiceClient mlServiceClient,
			XrayFailureRouter failureRouter,
			SseNotifier sseNotifier) {
		this.xrayRequestService = xrayRequestService;
		this.repository = repository;
		this.storageService = storageService;
		this.mlServiceClient = mlServiceClient;
		this.failureRouter = failureRouter;
		this.sseNotifier = sseNotifier;
	}

	@RabbitListener(queues = RabbitTopologyConfig.MAIN_QUEUE)
	public void onMessage(XrayProcessingRequested message, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag)
			throws IOException {

		UUID id = message.xrayRequestId();
		MDC.put(CORRELATION_ID, id.toString());

		try {
			XrayRequest owned = xrayRequestService.markProcessing(id);

			if (owned == null) {
				log.info("X-ray request {} is no longer QUEUED/RETRYING - already handled elsewhere, skipping", id);
				channel.basicAck(deliveryTag, false);
				return;
			}

			String userId = owned.userId();
			XrayRequestResponse notifyPayload;

			try {
				Resource file = storageService.load(owned.storagePath());
				PredictResponse prediction = mlServiceClient.predict(file, id.toString());

				XrayRequest done = xrayRequestService.applySuccess(
					id, prediction.pneumonia(), prediction.confidence(), prediction.modelVersion());

				notifyPayload = XrayRequestResponse.from(done);
				log.info(
					"X-ray request {} processed: pneumonia={} confidence={} modelVersion={}",
					id, prediction.pneumonia(), prediction.confidence(), prediction.modelVersion());

			} catch (NotChestXrayException e) {

				// Content invalidation, not a technical failure: the same bytes will fail this
				// same check on every future attempt, so this SHALL NOT go through
				// XrayFailureRouter's retry/dead-letter branch - straight to the terminal,
				// non-retryable INVALID status instead.
				log.warn("X-ray request {} rejected as not a chest X-ray: {}", id, e.getMessage());

				XrayRequest invalid = xrayRequestService.applyInvalid(id, e.getMessage());
				notifyPayload = XrayRequestResponse.from(invalid);

			} catch (Exception e) {

				log.warn("X-ray request {} processing attempt failed: {}", id, e.toString());

				// Re-read retryCount fresh rather than reusing the value captured by
				// markProcessing() above, per spec - functionally equivalent today (nothing
				// else can touch this row while it's PROCESSING, under concurrency=1), but
				// this is the one place a future change to that assumption would matter, and
				// the fresh read costs one cheap query.
				int currentRetryCount = repository.findById(id).orElseThrow().retryCount();
				XrayStatus outcome = failureRouter.routeFailure(id, currentRetryCount);
				XrayRequest updated = repository.findById(id).orElseThrow();

				notifyPayload = XrayRequestResponse.from(updated);
				log.info("X-ray request {} routed to {}", id, outcome);
			}

			sseNotifier.notify(userId, notifyPayload);
			channel.basicAck(deliveryTag, false);

		} finally {
			MDC.remove(CORRELATION_ID);
		}
	}
}
