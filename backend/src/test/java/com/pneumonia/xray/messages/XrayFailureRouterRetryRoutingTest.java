package com.pneumonia.xray.messages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.pneumonia.xray.models.XrayStatus;
import com.pneumonia.xray.daos.XrayRequestDAO;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Focused, Spring-context-free proof of the retry/DLQ branching decision (DEC-0001 items 5 and
 * 14; PLN-0001 Test Requirements). No Postgres, no RabbitMQ - both collaborators are mocked.
 */
@ExtendWith(MockitoExtension.class)
class XrayFailureRouterRetryRoutingTest {

	@Mock
	private XrayRequestDAO repository;

	@Mock
	private RabbitTemplate rabbitTemplate;

	@Test
	void retryCountBelowThreshold_routesToRetryQueue_notDlq() {

		XrayFailureRouter router = new XrayFailureRouter(repository, rabbitTemplate);
		UUID id = UUID.randomUUID();

		XrayStatus result = router.routeFailure(id, 2);

		assertThat(result).isEqualTo(XrayStatus.RETRYING);

		verify(rabbitTemplate).convertAndSend(
			eq(""), eq(XrayFailureRouter.RETRY_QUEUE), eq(new XrayProcessingRequested(id)));
		verify(rabbitTemplate, never()).convertAndSend(
			eq(""), eq(XrayFailureRouter.DEAD_LETTER_QUEUE), any(XrayProcessingRequested.class));

		verify(repository).transitionStatusWithRetryCount(
			eq(id), eq(XrayStatus.RETRYING), eq(3), retryingFrom(), any(Instant.class));
	}

	@Test
	void retryCountAtThreshold_routesToDlq_notRetryQueue() {

		XrayFailureRouter router = new XrayFailureRouter(repository, rabbitTemplate);
		UUID id = UUID.randomUUID();

		XrayStatus result = router.routeFailure(id, 3);

		assertThat(result).isEqualTo(XrayStatus.FAILED);

		verify(rabbitTemplate).convertAndSend(
			eq(""), eq(XrayFailureRouter.DEAD_LETTER_QUEUE), eq(new XrayProcessingRequested(id)));
		verify(rabbitTemplate, never()).convertAndSend(
			eq(""), eq(XrayFailureRouter.RETRY_QUEUE), any(XrayProcessingRequested.class));

		verify(repository).transitionStatusWithRetryCount(
			eq(id), eq(XrayStatus.FAILED), eq(3), retryingFrom(), any(Instant.class));
	}

	private static Collection<XrayStatus> retryingFrom() {
		return eq(List.of(XrayStatus.PROCESSING));
	}
}
