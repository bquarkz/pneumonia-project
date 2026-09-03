package com.pneumonia.xray.dtos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Proves the ML service's {@code snake_case} {@code model_version} field actually maps onto
 * {@link PredictResponse#modelVersion()} via {@code @JsonProperty} under Jackson 3 (databind
 * moved to {@code tools.jackson}, but {@code jackson-annotations} - and therefore
 * {@code @JsonProperty} - did not, per {@link PredictResponse}'s own javadoc). Silent failure
 * mode if this were wrong: every {@code DONE} row would persist {@code model_version = null}
 * with no error anywhere, quietly violating DEC-0001 item 18 and the canonical JSON contract -
 * worth a dedicated, decisive check rather than trusting the dependency-tree inspection alone.
 */
class PredictResponseMappingTest {

	@Test
	void modelVersionField_mapsFromSnakeCaseJson() {

		JsonMapper mapper = JsonMapper.builder().build();

		PredictResponse response = mapper.readValue(
			"""
			{"pneumonia": true, "confidence": 0.87, "model_version": "placeholder-0.0.0"}
			""",
			PredictResponse.class);

		assertThat(response.pneumonia()).isTrue();
		assertThat(response.confidence()).isEqualTo(0.87);
		assertThat(response.modelVersion()).isEqualTo("placeholder-0.0.0");
	}
}
