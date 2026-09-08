package com.pneumonia.xray.dtos;

import com.pneumonia.xray.models.XrayRequest;
import com.pneumonia.xray.models.XrayStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * The canonical JSON shape shared identically by every REST response and every SSE
 * {@code status-update} event for an X-ray request. Field names and presence are a fixed
 * contract with the frontend - do not rename or add/remove fields here without updating that
 * contract.
 */
public record XrayRequestResponse(
	UUID id,
	String originalFilename,
	XrayStatus status,
	Boolean resultPneumonia,
	Double resultConfidence,
	String modelVersion,
	String rejectionReason,
	Instant createdAt) {

	public static XrayRequestResponse from(XrayRequest entity) {
		return new XrayRequestResponse(
			entity.id(),
			entity.originalFilename(),
			entity.status(),
			entity.resultPneumonia(),
			entity.resultConfidence(),
			entity.modelVersion(),
			entity.rejectionReason(),
			entity.createdAt());
	}
}
