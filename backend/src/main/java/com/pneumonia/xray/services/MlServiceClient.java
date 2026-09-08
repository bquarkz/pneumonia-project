package com.pneumonia.xray.services;

import com.pneumonia.xray.dtos.PredictResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Thin client for the ML service's {@code POST /predict} contract: multipart file field
 * {@code file}, header {@code X-Correlation-Id} carrying the X-ray request's own id (the same
 * value populated into the MDC {@code correlationId} key around this call), returns
 * {@code {pneumonia, confidence, model_version}}. A {@code 422} response whose body matches the
 * out-of-distribution guardrail's exact {@code {"detail": "<string>"}} shape - the guardrail
 * rejecting the image as not a chest X-ray - is translated into {@link NotChestXrayException}
 * carrying the guardrail's own explanation, so callers can tell this content invalidation apart
 * from a technical failure worth retrying. Any other {@code 422} (empty/malformed body, or
 * FastAPI/Starlette's own request-validation shape, whose {@code detail} is an array, not a
 * string) is NOT a recognized content rejection and is re-thrown as-is, so it falls through to
 * the normal technical-failure handling instead of being misclassified as a confident "this
 * image is invalid" verdict.
 */
@Component
public class MlServiceClient {

	private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

	private final RestClient restClient;

	MlServiceClient(
			@Value("${xray.ml-service.url}") String baseUrl,
			@Value("${xray.ml-service.connect-timeout-ms}") int connectTimeoutMs,
			@Value("${xray.ml-service.read-timeout-ms}") int readTimeoutMs) {

		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(connectTimeoutMs);
		requestFactory.setReadTimeout(readTimeoutMs);

		// Built directly rather than injecting Spring Boot's auto-configured RestClient.Builder:
		// this client's needs (a base URL and two timeouts) don't warrant depending on that
		// bean, and building it directly keeps this component fully self-contained.
		this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
	}

	public PredictResponse predict(Resource file, String correlationId) {

		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		body.add("file", file);

		try {
			return restClient.post()
				.uri("/predict")
				.header(CORRELATION_ID_HEADER, correlationId)
				.contentType(MediaType.MULTIPART_FORM_DATA)
				.body(body)
				.retrieve()
				.body(PredictResponse.class);
		} catch (HttpClientErrorException.UnprocessableContent e) {
			String detail = guardrailDetail(e);
			if (detail == null) {
				throw e;
			}
			throw new NotChestXrayException(detail, e);
		}
	}

	/**
	 * Returns the guardrail's rejection message, or {@literal null} if the 422 body isn't the
	 * expected {@code {"detail": "<string>"}} shape (empty body, no/wrong content type, or a
	 * {@code detail} that isn't a plain string) - deliberately not distinguishing between those
	 * cases, since none of them are a recognized content rejection.
	 */
	private static String guardrailDetail(HttpClientErrorException.UnprocessableContent e) {
		try {
			RejectionBody rejection = e.getResponseBodyAs(RejectionBody.class);
			return rejection == null ? null : rejection.detail();
		} catch (RestClientException conversionFailure) {
			return null;
		}
	}

	/** Shape of the ml-service's {@code 422} body: FastAPI's default {@code HTTPException} JSON. */
	private record RejectionBody(String detail) {
	}
}
