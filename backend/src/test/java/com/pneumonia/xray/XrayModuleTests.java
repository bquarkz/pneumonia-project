package com.pneumonia.xray;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pneumonia.xray.dtos.XrayRequestResponse;
import com.pneumonia.xray.messages.XrayProcessingRequested;
import com.pneumonia.xray.models.XrayRequest;
import com.pneumonia.xray.models.XrayStatus;
import com.pneumonia.xray.daos.XrayRequestDAO;
import com.pneumonia.xray.services.XrayRequestService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.modulith.test.PublishedEvents;
import org.springframework.transaction.annotation.Transactional;

/**
 * Representative use case for the {@code xray} module: uploading a valid batch stores the
 * file, persists it as {@code QUEUED}, and publishes {@link XrayProcessingRequested} - proving
 * the upload-to-enqueue path this module owns end to end, against a real (H2) repository.
 *
 * <p>{@code DIRECT_DEPENDENCIES} bootstrap mode is used because {@code xray} genuinely depends
 * on {@code infra} ({@code StorageService}) and {@code users} ({@code LocalUserService}) - see
 * {@code ModularityTests}' printed module structure. That transitively pulls in
 * {@code infra.SecurityConfig} (needing its {@code keycloak.issuer-uri}/{@code
 * keycloak.public-issuer-uri} dummy properties, same rationale as {@code InfraModuleTests}) and
 * RabbitMQ auto-configuration; the {@code RabbitTemplate} bean is
 * replaced with a pre-stubbed mock so nothing here needs a live broker, and
 * {@code spring.rabbitmq.listener.simple.auto-startup} is disabled test-wide (see
 * {@code src/test/resources/application.yml}) so {@link XrayProcessingConsumer}'s listener
 * container never tries to actually connect and consume - deliberately narrowed exactly as
 * PLN-0001's Test Requirements anticipates for these module tests, in favor of
 * Testcontainers-backed Postgres/RabbitMQ.
 */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
class XrayModuleTests {

	@TestConfiguration
	static class StubBeans {

		/**
		 * A mock, not the real auto-configured template: this repository has no RabbitMQ
		 * broker available to it. Stubbed with the same kind of converter production code
		 * uses ({@link JacksonJsonMessageConverter}, not {@code SimpleMessageConverter} -
		 * which rejects any payload that isn't {@code String}/{@code byte[]}/
		 * {@code Serializable}, and {@code XrayProcessingRequested} is none of those) - and
		 * stubbed here, at bean-construction time, rather than left blank as
		 * {@code @MockitoBean} would (to be stubbed later in a {@code @BeforeEach}): Spring
		 * Modulith's AMQP event externalization runs asynchronously and wraps whatever this
		 * bean's {@code getMessageConverter()} returns into a {@code RabbitMessagingTemplate}
		 * AT BEAN CREATION TIME, so stubbing any later is too late - that wrapping already
		 * happened during context startup by then.
		 */
		@Bean
		@Primary
		RabbitTemplate rabbitTemplate(tools.jackson.databind.json.JsonMapper jsonMapper) {
			RabbitTemplate template = mock(RabbitTemplate.class);
			when(template.getMessageConverter()).thenReturn(new JacksonJsonMessageConverter(jsonMapper));
			return template;
		}
	}

	@Autowired
	private XrayRequestService xrayRequestService;

	@Autowired
	private XrayRequestDAO xrayRequestDAO;

	@Test
	void uploadingAValidBatch_persistsAsQueued_andPublishesProcessingRequested(PublishedEvents events) {

		MockMultipartFile jpeg =
			new MockMultipartFile("files", "chest1.jpg", "image/jpeg", "pretend-jpeg-bytes".getBytes());

		List<XrayRequestResponse> created = xrayRequestService.uploadBatch("keycloak-subject-abc", List.of(jpeg));

		assertThat(created).hasSize(1);
		XrayRequestResponse response = created.get(0);
		assertThat(response.status()).isEqualTo(XrayStatus.QUEUED);
		assertThat(response.originalFilename()).isEqualTo("chest1.jpg");

		XrayRequest persisted = xrayRequestDAO.findById(response.id()).orElseThrow();
		assertThat(persisted.status()).isEqualTo(XrayStatus.QUEUED);
		assertThat(persisted.userId()).isEqualTo("keycloak-subject-abc");

		var published = events.ofType(XrayProcessingRequested.class);
		assertThat(published).anyMatch(event -> event.xrayRequestId().equals(response.id()));
	}

	/**
	 * PLN-0001 Test Requirements: "a test asserting the conditional-update transition method
	 * returns 0 affected rows when the {@code from} state does not match" - the idempotency
	 * guard every FSM transition (upload, consumer, manual retry) relies on to treat "someone
	 * else already handled this" as a safe no-op rather than an error. Exercised here, against
	 * the real H2-backed repository, rather than as a plain Mockito test, precisely because
	 * what needs proving is the SQL conditional {@code WHERE ... status IN (...)} actually
	 * matching (or not matching) rows in a real database, not just that the method was called.
	 *
	 * <p>{@code @Transactional} here (auto-rolled-back by Spring's test support, as usual) is
	 * for test isolation only - plain {@code JdbcTemplate} calls need no active transaction to
	 * execute, unlike the JPA {@code @Modifying} queries this repository used to be.
	 */
	@Test
	@Transactional
	void transitionStatus_returnsZeroAffectedRows_whenCurrentStatusNotInFromSet() {

		MockMultipartFile jpeg =
			new MockMultipartFile("files", "chest2.jpg", "image/jpeg", "pretend-jpeg-bytes".getBytes());
		XrayRequestResponse queued = xrayRequestService.uploadBatch("keycloak-subject-def", List.of(jpeg)).get(0);

		// The row is QUEUED, not FAILED - a transition guarded on FAILED must affect nothing.
		int affected = xrayRequestDAO.transitionStatus(
			queued.id(), XrayStatus.DONE, List.of(XrayStatus.FAILED), Instant.now());

		assertThat(affected).isZero();

		XrayRequest unchanged = xrayRequestDAO.findById(queued.id()).orElseThrow();
		assertThat(unchanged.status()).isEqualTo(XrayStatus.QUEUED);
	}

	/**
	 * Exercises the one path the other tests here don't: {@link XrayRequestDAO#applyResult}
	 * and {@link XrayRequest#ROW_MAPPER} reading back a row whose result columns are actually
	 * non-{@literal null} - every other test in this class only ever sees a freshly-{@code
	 * QUEUED} row, where those columns are {@literal null}.
	 */
	@Test
	void applyingSuccess_transitionsToDone_andPersistsMlResult() {

		MockMultipartFile jpeg =
			new MockMultipartFile("files", "chest3.jpg", "image/jpeg", "pretend-jpeg-bytes".getBytes());
		XrayRequestResponse queued = xrayRequestService.uploadBatch("keycloak-subject-ghi", List.of(jpeg)).get(0);

		xrayRequestService.markProcessing(queued.id());
		XrayRequest done = xrayRequestService.applySuccess(queued.id(), true, 0.87, "model-v1");

		assertThat(done.status()).isEqualTo(XrayStatus.DONE);
		assertThat(done.resultPneumonia()).isTrue();
		assertThat(done.resultConfidence()).isEqualTo(0.87);
		assertThat(done.modelVersion()).isEqualTo("model-v1");
		assertThat(done.processedAt()).isNotNull();

		XrayRequest reloaded = xrayRequestDAO.findById(queued.id()).orElseThrow();
		assertThat(reloaded.status()).isEqualTo(XrayStatus.DONE);
		assertThat(reloaded.resultPneumonia()).isTrue();
		assertThat(reloaded.resultConfidence()).isEqualTo(0.87);
		assertThat(reloaded.modelVersion()).isEqualTo("model-v1");
	}

	/**
	 * Proves the content-invalidation path lands on the terminal {@code INVALID} status with
	 * the ml-service's rejection reason persisted - never {@code RETRYING}/{@code FAILED}, and
	 * {@code retry_count} left genuinely untouched (not merely still at its default), since this
	 * transition never originates from a retry attempt. The row is driven through one real
	 * {@code RETRYING} cycle first (retry_count=1) precisely so this test can tell "left
	 * untouched" apart from "always zero regardless" / "reset to zero" - a scenario starting
	 * from a fresh, never-retried row cannot distinguish those.
	 */
	@Test
	void applyingInvalid_transitionsToInvalid_andPersistsRejectionReason_leavingNonZeroRetryCountUntouched() {

		MockMultipartFile jpeg =
			new MockMultipartFile("files", "not-a-chest.jpg", "image/jpeg", "pretend-jpeg-bytes".getBytes());
		XrayRequestResponse queued = xrayRequestService.uploadBatch("keycloak-subject-jkl", List.of(jpeg)).get(0);

		xrayRequestService.markProcessing(queued.id());
		xrayRequestDAO.transitionStatusWithRetryCount(
			queued.id(), XrayStatus.RETRYING, 1, List.of(XrayStatus.PROCESSING), Instant.now());
		xrayRequestService.markProcessing(queued.id());

		XrayRequest invalid = xrayRequestService.applyInvalid(
			queued.id(), "Uploaded image does not look like a chest X-ray (expected a grayscale scan).");

		assertThat(invalid.status()).isEqualTo(XrayStatus.INVALID);
		assertThat(invalid.rejectionReason())
			.isEqualTo("Uploaded image does not look like a chest X-ray (expected a grayscale scan).");
		assertThat(invalid.retryCount()).isEqualTo(1);

		XrayRequest reloaded = xrayRequestDAO.findById(queued.id()).orElseThrow();
		assertThat(reloaded.status()).isEqualTo(XrayStatus.INVALID);
		assertThat(reloaded.rejectionReason())
			.isEqualTo("Uploaded image does not look like a chest X-ray (expected a grayscale scan).");
		assertThat(reloaded.retryCount()).isEqualTo(1);
	}
}
