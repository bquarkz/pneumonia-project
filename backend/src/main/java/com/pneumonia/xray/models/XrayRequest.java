package com.pneumonia.xray.models;

import com.pneumonia.xray.daos.XrayRequestDAO;

import java.time.Instant;
import java.util.UUID;

/**
 * A single X-ray request's FSM state and metadata, mapped 1:1 onto the {@code xray_request}
 * table (see {@code V1__create_xray_request.sql}). {@code id} doubles as the correlation id
 * used throughout backend logs and the ML service call.
 *
 * <p>State transitions SHALL go through {@link XrayRequestDAO}'s conditional-update
 * methods, never a plain {@code save()} of a mutated status field, so that idempotency holds
 * when more than one trigger (consumer, manual retry) might race to move the same request.
 */
public record XrayRequest(
		UUID id,
		String userId,
		String originalFilename,
		String storagePath,
		XrayStatus status,
		int retryCount,
		Boolean resultPneumonia,
		Double resultConfidence,
		String modelVersion,
	  	Instant createdAt,
		Instant updatedAt,
		Instant processedAt) {

	public XrayRequest(UUID id, String userId, String originalFilename, String storagePath, XrayStatus status) {
		this(id, userId, originalFilename, storagePath, status, Instant.now());
	}

	private XrayRequest(UUID id, String userId, String originalFilename, String storagePath, XrayStatus status, Instant now) {
		this(id, userId, originalFilename, storagePath, status, 0, null, null, null, now, now, null);
	}
}
