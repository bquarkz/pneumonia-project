package com.pneumonia.xray.messages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pneumonia.infra.api.StorageService;
import com.pneumonia.xray.daos.XrayRequestDAO;
import com.pneumonia.xray.dtos.XrayRequestResponse;
import com.pneumonia.xray.models.XrayRequest;
import com.pneumonia.xray.models.XrayStatus;
import com.pneumonia.xray.services.MlServiceClient;
import com.pneumonia.xray.services.NotChestXrayException;
import com.pneumonia.xray.services.SseNotifier;
import com.pneumonia.xray.services.XrayRequestService;
import com.rabbitmq.client.Channel;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

/**
 * Proves the branch this module was missing before the ml-service's out-of-distribution
 * guardrail existed: a {@link NotChestXrayException} from {@link MlServiceClient#predict} is
 * content invalidation, not a technical failure, and SHALL NOT reach {@link XrayFailureRouter}
 * (no retry, no dead-letter, no retry-count increment) - it goes straight to the terminal
 * {@code INVALID} status via {@link XrayRequestService#applyInvalid}. A second test proves any
 * other exception is unaffected by this change and still routes through the existing
 * retry/dead-letter branch.
 */
@ExtendWith(MockitoExtension.class)
class XrayProcessingConsumerInvalidRoutingTest {

	@Mock
	private XrayRequestService xrayRequestService;

	@Mock
	private XrayRequestDAO repository;

	@Mock
	private StorageService storageService;

	@Mock
	private MlServiceClient mlServiceClient;

	@Mock
	private XrayFailureRouter failureRouter;

	@Mock
	private SseNotifier sseNotifier;

	@Mock
	private Channel channel;

	@Test
	void notChestXrayException_routesToInvalid_neverThroughFailureRouter() throws Exception {

		UUID id = UUID.randomUUID();
		Instant now = Instant.now();
		XrayRequest owned = new XrayRequest(id, "user-1", "chest.jpg", "path/chest.jpg", XrayStatus.PROCESSING);
		XrayRequest invalid = new XrayRequest(
			id, "user-1", "chest.jpg", "path/chest.jpg", XrayStatus.INVALID, 0, null, null, null,
			"Uploaded image does not look like a chest X-ray.", now, now, now);
		Resource resource = new ByteArrayResource("pretend-jpeg-bytes".getBytes());

		when(xrayRequestService.markProcessing(id)).thenReturn(owned);
		when(storageService.load(owned.storagePath())).thenReturn(resource);
		when(mlServiceClient.predict(resource, id.toString()))
			.thenThrow(new NotChestXrayException("Uploaded image does not look like a chest X-ray.", null));
		when(xrayRequestService.applyInvalid(id, "Uploaded image does not look like a chest X-ray."))
			.thenReturn(invalid);

		XrayProcessingConsumer consumer = new XrayProcessingConsumer(
			xrayRequestService, repository, storageService, mlServiceClient, failureRouter, sseNotifier);

		consumer.onMessage(new XrayProcessingRequested(id), channel, 7L);

		verify(xrayRequestService).applyInvalid(id, "Uploaded image does not look like a chest X-ray.");
		verify(failureRouter, never()).routeFailure(any(), anyInt());
		verify(repository, never()).findById(any());
		verify(channel).basicAck(7L, false);

		ArgumentCaptor<XrayRequestResponse> notified = ArgumentCaptor.forClass(XrayRequestResponse.class);
		verify(sseNotifier).notify(eq("user-1"), notified.capture());
		assertThat(notified.getValue().status()).isEqualTo(XrayStatus.INVALID);
		assertThat(notified.getValue().rejectionReason()).isEqualTo("Uploaded image does not look like a chest X-ray.");
	}

	@Test
	void otherException_stillRoutesThroughFailureRouter_notInvalid() throws Exception {

		UUID id = UUID.randomUUID();
		XrayRequest owned = new XrayRequest(id, "user-1", "chest.jpg", "path/chest.jpg", XrayStatus.PROCESSING);
		XrayRequest afterFailure = new XrayRequest(id, "user-1", "chest.jpg", "path/chest.jpg", XrayStatus.RETRYING);
		Resource resource = new ByteArrayResource("pretend-jpeg-bytes".getBytes());

		when(xrayRequestService.markProcessing(id)).thenReturn(owned);
		when(storageService.load(owned.storagePath())).thenReturn(resource);
		when(mlServiceClient.predict(resource, id.toString())).thenThrow(new RuntimeException("ml-service unavailable"));
		when(repository.findById(id)).thenReturn(java.util.Optional.of(owned), java.util.Optional.of(afterFailure));
		when(failureRouter.routeFailure(id, 0)).thenReturn(XrayStatus.RETRYING);

		XrayProcessingConsumer consumer = new XrayProcessingConsumer(
			xrayRequestService, repository, storageService, mlServiceClient, failureRouter, sseNotifier);

		consumer.onMessage(new XrayProcessingRequested(id), channel, 9L);

		verify(failureRouter).routeFailure(id, 0);
		verify(xrayRequestService, never()).applyInvalid(any(), any());
		verify(channel).basicAck(9L, false);
	}
}
