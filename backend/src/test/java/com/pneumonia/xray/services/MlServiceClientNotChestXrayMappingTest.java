package com.pneumonia.xray.services;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Proves the ml-service's {@code 422} out-of-distribution rejection is translated into
 * {@link NotChestXrayException} carrying the guardrail's own detail message - the boundary
 * {@link MlServiceClient} added to stop {@code XrayProcessingConsumer} from treating "not a
 * chest X-ray" as a retryable technical failure. Also proves the inverse: a {@code 422} that
 * does NOT match the guardrail's exact {@code {"detail": "<string>"}} shape - empty body, or
 * FastAPI/Starlette's own request-validation shape (a {@code detail} array instead of a string)
 * - is NOT misclassified as a content rejection; it propagates as the original
 * {@link HttpClientErrorException}, so {@code XrayProcessingConsumer} still routes it through
 * the normal retry/dead-letter path instead of the (unearned) terminal {@code INVALID} status.
 * Exercised against a real embedded HTTP server (JDK's {@link HttpServer}, no extra dependency)
 * rather than a mock, since what is actually under test is parsing of a real {@code 422}
 * response body via Spring's own {@code getResponseBodyAs}.
 */
class MlServiceClientNotChestXrayMappingTest {

	private HttpServer server;

	@AfterEach
	void stopServer() {
		if (server != null) {
			server.stop(0);
		}
	}

	@Test
	void rejectionResponse_isTranslatedToNotChestXrayException_withGuardrailDetail() throws IOException {

		startServerRespondingWith(422, "application/json", "{\"detail\": \"Uploaded image does not look like a chest X-ray.\"}");

		MlServiceClient client = clientFor(server);

		assertThatThrownBy(() -> client.predict(new ByteArrayResource("bytes".getBytes()), "corr-1"))
			.isInstanceOf(NotChestXrayException.class)
			.hasMessage("Uploaded image does not look like a chest X-ray.");
	}

	@Test
	void emptyRejectionBody_isNotMisclassified_propagatesAsTheOriginalHttpError() throws IOException {

		startServerRespondingWith(422, null, "");

		MlServiceClient client = clientFor(server);

		assertThatThrownBy(() -> client.predict(new ByteArrayResource("bytes".getBytes()), "corr-1"))
			.isInstanceOf(HttpClientErrorException.UnprocessableContent.class)
			.isNotInstanceOf(NotChestXrayException.class);
	}

	/**
	 * FastAPI/Starlette's own request-validation {@code 422} (e.g. a malformed multipart
	 * request) shapes {@code detail} as an array of error objects, not a plain string - unlike
	 * the guardrail's {@code HTTPException(422, detail=str(exc))}. This is a different failure
	 * mode from the ml-service actually understanding the image and rejecting it.
	 */
	@Test
	void validationErrorShapedRejectionBody_isNotMisclassified_propagatesAsTheOriginalHttpError() throws IOException {

		startServerRespondingWith(
			422, "application/json", "{\"detail\": [{\"loc\": [\"body\", \"file\"], \"msg\": \"field required\"}]}");

		MlServiceClient client = clientFor(server);

		assertThatThrownBy(() -> client.predict(new ByteArrayResource("bytes".getBytes()), "corr-1"))
			.isInstanceOf(HttpClientErrorException.UnprocessableContent.class)
			.isNotInstanceOf(NotChestXrayException.class);
	}

	private void startServerRespondingWith(int status, String contentType, String responseBody) throws IOException {

		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/predict", exchange -> {
			byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
			if (contentType != null) {
				exchange.getResponseHeaders().add("Content-Type", contentType);
			}
			exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
			if (bytes.length > 0) {
				exchange.getResponseBody().write(bytes);
			}
			exchange.close();
		});
		server.start();
	}

	private static MlServiceClient clientFor(HttpServer server) {
		return new MlServiceClient("http://localhost:" + server.getAddress().getPort(), 5000, 5000);
	}
}
