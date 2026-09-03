package com.pneumonia.xray.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.pneumonia.infra.api.StorageService;
import com.pneumonia.xray.daos.XrayRequestDAO;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Proves the batch upload validation contract (DEC-0001 item 7 / PLN-0001 Test Requirements):
 * a batch containing a non-JPEG part, or exceeding the 10-file cap, is rejected with 400,
 * atomically, before anything in the batch is stored or persisted. Exercised directly against
 * {@link XrayRequestService}, where the validation actually lives
 * (not a MockMvc round-trip: the mapping from {@link ResponseStatusException} to an HTTP 400
 * response is standard Spring MVC framework behavior, not application logic this project needs
 * to re-prove).
 */
@ExtendWith(MockitoExtension.class)
class XrayRequestServiceUploadValidationTest {

	@Mock
	private XrayRequestDAO repository;

	@Mock
	private StorageService storageService;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	@Mock
	private SseNotifier sseNotifier;

	@Test
	void batchWithNonJpegPart_isRejectedWith400_andPersistsNothing() {

		XrayRequestService service = new XrayRequestService(repository, storageService, eventPublisher, sseNotifier);

		MockMultipartFile jpeg =
			new MockMultipartFile("files", "chest1.jpg", "image/jpeg", "not-really-a-jpeg".getBytes());
		MockMultipartFile notJpeg =
			new MockMultipartFile("files", "notes.txt", "text/plain", "hello".getBytes());

		assertThatThrownBy(() -> service.uploadBatch("user-1", List.of(jpeg, notJpeg)))
			.isInstanceOf(ResponseStatusException.class)
			.satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST));

		verifyNoInteractions(storageService);
		verifyNoInteractions(eventPublisher);
		verifyNoInteractions(sseNotifier);
		verify(repository, never()).insert(any());
	}

	@Test
	void batchWithMoreThanTenFiles_isRejectedWith400_andPersistsNothing() {

		XrayRequestService service = new XrayRequestService(repository, storageService, eventPublisher, sseNotifier);

		List<MultipartFile> elevenJpegs = IntStream.rangeClosed(1, 11)
			.<MultipartFile>mapToObj(
				i -> new MockMultipartFile("files", "chest" + i + ".jpg", "image/jpeg", "not-really-a-jpeg".getBytes()))
			.toList();

		assertThatThrownBy(() -> service.uploadBatch("user-1", elevenJpegs))
			.isInstanceOf(ResponseStatusException.class)
			.satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST));

		verifyNoInteractions(storageService);
		verifyNoInteractions(eventPublisher);
		verifyNoInteractions(sseNotifier);
		verify(repository, never()).insert(any());
	}
}
