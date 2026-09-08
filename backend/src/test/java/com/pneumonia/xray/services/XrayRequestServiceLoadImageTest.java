package com.pneumonia.xray.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pneumonia.infra.api.StorageService;
import com.pneumonia.xray.daos.XrayRequestDAO;
import com.pneumonia.xray.models.XrayRequest;
import com.pneumonia.xray.models.XrayStatus;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Proves {@link XrayRequestService#loadImage} shares {@link XrayRequestService#retry}'s exact
 * ownership guard: a request that doesn't belong to the caller - whether it never existed or
 * belongs to someone else - is rejected with 404 without ever reaching {@link StorageService},
 * regardless of the request's current status (unlike {@code retry}, viewing the original image
 * is not restricted to any particular {@code XrayStatus}).
 */
@ExtendWith(MockitoExtension.class)
class XrayRequestServiceLoadImageTest {

	@Mock
	private XrayRequestDAO repository;

	@Mock
	private StorageService storageService;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	@Mock
	private SseNotifier sseNotifier;

	@Test
	void loadImage_whenOwnedByCaller_returnsTheStoredResource() {

		XrayRequestService service = new XrayRequestService(repository, storageService, eventPublisher, sseNotifier);

		UUID id = UUID.randomUUID();
		XrayRequest owned = new XrayRequest(id, "user-1", "chest.jpg", "path/chest.jpg", XrayStatus.DONE);
		Resource resource = new ByteArrayResource("jpeg-bytes".getBytes());

		when(repository.findByIdAndUserId(id, "user-1")).thenReturn(Optional.of(owned));
		when(storageService.load("path/chest.jpg")).thenReturn(resource);

		assertThat(service.loadImage("user-1", id)).isSameAs(resource);
	}

	@Test
	void loadImage_whenNotOwnedByCaller_isRejectedWith404_withoutTouchingStorage() {

		XrayRequestService service = new XrayRequestService(repository, storageService, eventPublisher, sseNotifier);

		UUID id = UUID.randomUUID();
		when(repository.findByIdAndUserId(id, "user-1")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.loadImage("user-1", id))
			.isInstanceOf(ResponseStatusException.class)
			.satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

		verifyNoInteractions(storageService);
	}
}
