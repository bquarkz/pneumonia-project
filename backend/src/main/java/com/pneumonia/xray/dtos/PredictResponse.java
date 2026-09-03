package com.pneumonia.xray.dtos;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response body of the ML service's {@code POST /predict}. {@code jackson-annotations}
 * (unlike {@code jackson-databind}, which moved to the {@code tools.jackson} package in
 * Jackson 3) keeps its {@code com.fasterxml.jackson.annotation} package, so this annotation
 * import is unaffected by that migration.
 */
public record PredictResponse(
	boolean pneumonia,
	double confidence,
	@JsonProperty("model_version") String modelVersion) {
}
