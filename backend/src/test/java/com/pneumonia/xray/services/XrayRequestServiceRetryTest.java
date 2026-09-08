package com.pneumonia.xray.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pneumonia.infra.api.StorageService;
import com.pneumonia.xray.daos.XrayRequestDAO;
import com.pneumonia.xray.dtos.XrayRequestResponse;
import com.pneumonia.xray.messages.XrayProcessingRequested;
import com.pneumonia.xray.models.XrayRequest;
import com.pneumonia.xray.models.XrayStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Proves the manual-retry endpoint's status guard (DEC-0001 item 13): only a {@code FAILED}
 * request may be retried. In particular, an {@code INVALID} request - the terminal,
 * non-retryable status a content rejection lands on - SHALL be rejected exactly like any other
 * non-{@code FAILED} status, with no transition and no re-publication of
 * {@link XrayProcessingRequested}. Previously untested at any level.
 */
@ExtendWith(MockitoExtension.class)
class XrayRequestServiceRetryTest {

	@Mock
	private XrayRequestDAO repository;

	@Mock
	private StorageService storageService;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	@Mock
	private SseNotifier sseNotifier;

	@Test
	void retry_whenFailed_transitionsToQueued_resetsRetryCount_andPublishesEvent() {

		XrayRequestService service = new XrayRequestService(repository, storageService, eventPublisher, sseNotifier);

		UUID id = UUID.randomUUID();
		XrayRequest failed = new XrayRequest(id, "user-1", "chest.jpg", "path/chest.jpg", XrayStatus.FAILED);
		XrayRequest requeued = new XrayRequest(id, "user-1", "chest.jpg", "path/chest.jpg", XrayStatus.QUEUED);

		when(repository.findByIdAndUserId(id, "user-1")).thenReturn(Optional.of(failed));
		when(repository.transitionStatusWithRetryCount(eq(id), eq(XrayStatus.QUEUED), eq(0), eq(List.of(XrayStatus.FAILED)), any()))
			.thenReturn(1);
		when(repository.findById(id)).thenReturn(Optional.of(requeued));

		XrayRequestResponse response = service.retry("user-1", id);

		assertThat(response.status()).isEqualTo(XrayStatus.QUEUED);
		verify(eventPublisher).publishEvent(new XrayProcessingRequested(id));
	}

	@Test
	void retry_whenInvalid_isRejectedWith409_neitherTransitionedNorRepublished() {

		XrayRequestService service = new XrayRequestService(repository, storageService, eventPublisher, sseNotifier);

		UUID id = UUID.randomUUID();
		XrayRequest invalid = new XrayRequest(id, "user-1", "chest.jpg", "path/chest.jpg", XrayStatus.INVALID);

		when(repository.findByIdAndUserId(id, "user-1")).thenReturn(Optional.of(invalid));

		assertThatThrownBy(() -> service.retry("user-1", id))
			.isInstanceOf(ResponseStatusException.class)
			.satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

		verify(repository, never()).transitionStatusWithRetryCount(any(), any(), anyInt(), any(), any());
		verify(eventPublisher, never()).publishEvent(any());
	}
}
