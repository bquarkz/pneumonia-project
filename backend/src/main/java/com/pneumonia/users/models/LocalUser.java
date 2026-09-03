package com.pneumonia.users.models;

import java.time.Instant;

public record LocalUser(String id, String email, String displayName, Instant createdAt) {
	public LocalUser(String id, String email, String displayName) {
		this(id, email, displayName, Instant.now());
	}
}
