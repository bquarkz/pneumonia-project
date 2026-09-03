package com.pneumonia.users;

import static org.assertj.core.api.Assertions.assertThat;

import com.pneumonia.users.models.LocalUser;
import com.pneumonia.users.daos.LocalUserDAO;
import com.pneumonia.users.api.LocalUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;

/**
 * Representative use case for the {@code users} module: resolving a {@link LocalUser} from a
 * Keycloak JWT principal creates it on first use, and resolves the SAME record (not a
 * duplicate) on a subsequent call for the same subject.
 *
 * <p>Runs against an in-memory H2 database (see {@code src/test/resources/application.yml}),
 * schema created by the real Flyway migrations in PostgreSQL compatibility mode - kept
 * deliberately narrow (see class javadoc on {@code ModularityTests} sibling tests) rather than
 * wired to Testcontainers-backed Postgres, per this project's stated test-scope trade-off.
 */
@ApplicationModuleTest
class UsersModuleTests {

	@Autowired
	private LocalUserService localUserService;

	@Autowired
	private LocalUserDAO localUserDAO;

	@Test
	void resolvesOrCreatesLocalUserFromJwt_idempotently() {

		Jwt jwt = jwt("keycloak-subject-123", "demo@example.com", "Demo User");

		LocalUser first = localUserService.resolveOrCreate(jwt);

		assertThat(first.id()).isEqualTo("keycloak-subject-123");
		assertThat(first.email()).isEqualTo("demo@example.com");
		assertThat(localUserDAO.count()).isEqualTo(1);

		LocalUser second = localUserService.resolveOrCreate(jwt);

		assertThat(second.id()).isEqualTo(first.id());
		assertThat(localUserDAO.count()).isEqualTo(1);
	}

	private static Jwt jwt(String subject, String email, String name) {
		Instant now = Instant.now();
		return Jwt.withTokenValue("test-token")
			.header("alg", "none")
			.claims(claims -> claims.putAll(Map.of("sub", subject, "email", email, "name", name)))
			.issuedAt(now)
			.expiresAt(now.plusSeconds(300))
			.build();
	}
}
