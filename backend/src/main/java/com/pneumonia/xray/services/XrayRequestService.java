package com.pneumonia.xray.services;

import com.pneumonia.infra.api.StorageService;
import com.pneumonia.xray.dtos.XrayRequestResponse;
import com.pneumonia.xray.messages.XrayProcessingRequested;
import com.pneumonia.xray.models.XrayRequest;
import com.pneumonia.xray.models.XrayStatus;
import com.pneumonia.xray.daos.XrayRequestDAO;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Owns the two ways an {@link XrayRequest} enters the processing pipeline: a fresh batch
 * upload, and a manual retry of a previously {@code FAILED} request. Both end the same way -
 * an {@link XrayProcessingRequested} event published via {@link ApplicationEventPublisher} -
 * so the RabbitMQ consumer (and everything downstream of it) cannot tell the two apart, by
 * design.
 */
@Service
public class XrayRequestService {

	private static final Logger log = LoggerFactory.getLogger(XrayRequestService.class);
	private static final String CORRELATION_ID = "correlationId";
	private static final String CONTENT_TYPE_JPEG = "image/jpeg";
	private static final int MAX_BATCH_SIZE = 10;

	private final XrayRequestDAO repository;
	private final StorageService storageService;
	private final ApplicationEventPublisher eventPublisher;
	private final SseNotifier sseNotifier;

	public XrayRequestService(
			XrayRequestDAO repository,
			StorageService storageService,
			ApplicationEventPublisher eventPublisher,
			SseNotifier sseNotifier) {
		this.repository = repository;
		this.storageService = storageService;
		this.eventPublisher = eventPublisher;
		this.sseNotifier = sseNotifier;
	}

	/**
	 * Validates the batch size (at most {@value #MAX_BATCH_SIZE} files) and that every part is
	 * a JPEG before persisting anything: a batch violating either rule is rejected as a whole
	 * (400), atomically - nothing in that batch is stored or gets a database row. Once
	 * validation passes, each file is stored, inserted as {@code RECEIVED}, transitioned to
	 * {@code QUEUED}, and published for processing, all within one transaction.
	 */
	@Transactional
	public List<XrayRequestResponse> uploadBatch(String userId, List<MultipartFile> files) {

		if (files == null || files.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one file is required");
		}

		if (files.size() > MAX_BATCH_SIZE) {
			throw new ResponseStatusException(
				HttpStatus.BAD_REQUEST,
				"At most " + MAX_BATCH_SIZE + " files are accepted per batch, received: " + files.size());
		}

		for (MultipartFile file : files) {
			if (!CONTENT_TYPE_JPEG.equalsIgnoreCase(file.getContentType())) {
				throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST,
					"Only JPEG files are accepted, rejected: " + file.getOriginalFilename());
			}
		}

		List<XrayRequestResponse> created = new ArrayList<>();

		for (MultipartFile file : files) {

			UUID id = UUID.randomUUID();
			MDC.put(CORRELATION_ID, id.toString());

			try {
				String storagePath = storageService.store(file, id);

				XrayRequest entity =
					new XrayRequest(id, userId, file.getOriginalFilename(), storagePath, XrayStatus.RECEIVED);
				repository.insert(entity);

				repository.transitionStatus(id, XrayStatus.QUEUED, List.of(XrayStatus.RECEIVED), Instant.now());

				XrayRequest queued = repository.findById(id).orElseThrow();
				log.info("X-ray request received and queued: {}", queued.originalFilename());

				eventPublisher.publishEvent(new XrayProcessingRequested(id));

				created.add(XrayRequestResponse.from(queued));
			} finally {
				MDC.remove(CORRELATION_ID);
			}
		}

		notifyAllAfterCommit(userId, created);

		return created;
	}

	@Transactional(readOnly = true)
	public List<XrayRequestResponse> listForUser(String userId) {
		return repository.findByUserIdOrderByCreatedAtDesc(userId).stream()
			.map(XrayRequestResponse::from)
			.toList();
	}

	/**
	 * Re-queues a {@code FAILED} request belonging to {@code userId}: resets its retry count
	 * to 0, transitions it back to {@code QUEUED}, and publishes the exact same
	 * {@link XrayProcessingRequested} event the original upload used - the RabbitMQ consumer
	 * treats this identically to a fresh enqueue.
	 *
	 * @throws ResponseStatusException 404 if no such request exists for this user; 409 if it
	 *     is not currently {@code FAILED}.
	 */
	@Transactional
	public XrayRequestResponse retry(String userId, UUID id) {

		MDC.put(CORRELATION_ID, id.toString());

		try {
			XrayRequest existing = repository.findByIdAndUserId(id, userId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "X-ray request not found"));

			if (existing.status() != XrayStatus.FAILED) {
				throw new ResponseStatusException(
					HttpStatus.CONFLICT, "Only FAILED requests can be retried, current status: " + existing.status());
			}

			int rows = repository.transitionStatusWithRetryCount(
				id, XrayStatus.QUEUED, 0, List.of(XrayStatus.FAILED), Instant.now());

			if (rows == 0) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "X-ray request state changed concurrently");
			}

			XrayRequest updated = repository.findById(id).orElseThrow();
			log.info("X-ray request manually retried: {}", updated.originalFilename());

			eventPublisher.publishEvent(new XrayProcessingRequested(id));

			XrayRequestResponse response = XrayRequestResponse.from(updated);
			notifyAfterCommit(userId, response);

			return response;
		} finally {
			MDC.remove(CORRELATION_ID);
		}
	}

	/**
	 * Conditionally, idempotently claims ownership of a queued/retrying request for
	 * processing. Returns {@literal null} (never throws) when the row's current status was
	 * not {@code QUEUED} or {@code RETRYING} - i.e. some other trigger already owns it - which
	 * {@code XrayProcessingConsumer} treats as a no-op.
	 *
	 * <p>Called only from {@code XrayProcessingConsumer}, a separate Spring bean in the
	 * {@code messages} package - not a private method on that class, since a
	 * private/self-invoked {@code @Transactional} method would silently bypass Spring's
	 * proxy-based transaction advice entirely.
	 */
	@Transactional
	public XrayRequest markProcessing(UUID id) {

		int rows = repository.transitionStatus(
			id, XrayStatus.PROCESSING, List.of(XrayStatus.QUEUED, XrayStatus.RETRYING), Instant.now());

		return rows == 0 ? null : repository.findById(id).orElseThrow();
	}

	/** Records a successful ML prediction and transitions PROCESSING -&gt; DONE. */
	@Transactional
	public XrayRequest applySuccess(UUID id, boolean pneumonia, double confidence, String modelVersion) {

		Instant now = Instant.now();
		repository.transitionStatus(id, XrayStatus.DONE, List.of(XrayStatus.PROCESSING), now);
		repository.applyResult(id, pneumonia, confidence, modelVersion, now);

		return repository.findById(id).orElseThrow();
	}

	/**
	 * Records the ML service's rejection of the uploaded image as not a chest X-ray and
	 * transitions PROCESSING -&gt; INVALID. Terminal and non-retryable, unlike {@code FAILED}:
	 * the same bytes will fail the same content check every time, so neither the retry/
	 * dead-letter router nor {@link #retry} ever route into or out of this status - it is
	 * content invalidation, not a technical error.
	 */
	@Transactional
	public XrayRequest applyInvalid(UUID id, String rejectionReason) {

		Instant now = Instant.now();
		repository.transitionToInvalid(id, rejectionReason, List.of(XrayStatus.PROCESSING), now);

		return repository.findById(id).orElseThrow();
	}

	private void notifyAllAfterCommit(String userId, List<XrayRequestResponse> responses) {
		runAfterCommit(() -> responses.forEach(response -> sseNotifier.notify(userId, response)));
	}

	private void notifyAfterCommit(String userId, XrayRequestResponse response) {
		runAfterCommit(() -> sseNotifier.notify(userId, response));
	}

	/**
	 * Defers SSE notification until after this transaction commits, so a subscriber never sees
	 * a status update for a row that a rollback then makes disappear. Falls back to notifying
	 * immediately when there is no active transaction synchronization (e.g. a plain unit test
	 * calling this service directly, outside a Spring-managed transaction).
	 */
	private void runAfterCommit(Runnable action) {

		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			action.run();
			return;
		}

		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				action.run();
			}
		});
	}
}
