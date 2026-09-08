package com.pneumonia.xray.services;

/**
 * Thrown when the ML service rejects an uploaded image as not a chest X-ray (HTTP 422 from
 * {@code POST /predict}'s out-of-distribution guardrail). Deliberately distinct from any other
 * {@link MlServiceClient#predict} failure: the same bytes will fail this content check every
 * time, so it is a content-invalidation, not a technical error worth retrying.
 */
public class NotChestXrayException extends RuntimeException {

	public NotChestXrayException(String message, Throwable cause) {
		super(message, cause);
	}
}
