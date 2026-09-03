package com.pneumonia.xray.services;

import com.pneumonia.xray.dtos.PredictResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Thin client for the ML service's {@code POST /predict} contract: multipart file field
 * {@code file}, header {@code X-Correlation-Id} carrying the X-ray request's own id (the same
 * value populated into the MDC {@code correlationId} key around this call), returns
 * {@code {pneumonia, confidence, model_version}}.
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

		return restClient.post()
			.uri("/predict")
			.header(CORRELATION_ID_HEADER, correlationId)
			.contentType(MediaType.MULTIPART_FORM_DATA)
			.body(body)
			.retrieve()
			.body(PredictResponse.class);
	}
}
