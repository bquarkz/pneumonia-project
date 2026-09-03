package com.pneumonia.users.api;

import com.pneumonia.users.models.LocalUser;
import com.pneumonia.users.daos.LocalUserDAO;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LocalUserService {

	private final LocalUserDAO repository;

	public LocalUserService(LocalUserDAO repository) {
		this.repository = repository;
	}

	@Transactional
	public LocalUser resolveOrCreate(Jwt jwt) {
		final var subject = jwt.getSubject();
		return repository.findById(subject).orElseGet(() -> {
			final var email = jwt.getClaimAsString("email");
			final var displayName = firstNonBlank(
				jwt.getClaimAsString("name"),
				jwt.getClaimAsString("preferred_username"),
				subject);
			final var localUser = new LocalUser(subject, email, displayName);
			return repository.insert(localUser);
		});
	}

	private static String firstNonBlank(String... candidates) {
		for (final var candidate : candidates) {
			if (StringUtils.isNotBlank(candidate)) {
				return candidate;
			}
		}
		return null;
	}
}
