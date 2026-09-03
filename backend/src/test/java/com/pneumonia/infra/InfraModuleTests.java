package com.pneumonia.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.pneumonia.infra.configs.SecurityConfig;
import com.pneumonia.infra.services.FilesystemStorageService;
import com.pneumonia.infra.api.StorageService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.modulith.test.ApplicationModuleTest;

/**
 * Representative use case for the {@code infra} module: {@link FilesystemStorageService}
 * round-trips a stored file back to identical bytes via {@link StorageService#load(String)}.
 *
 * <p>This module also contains {@link SecurityConfig}, which needs its own {@code
 * keycloak.issuer-uri}/{@code keycloak.public-issuer-uri} properties resolvable to build its
 * {@code JwtDecoder} bean - see {@code src/test/resources/application.yml} for the dummy
 * values used here (no live Keycloak in this test environment; JWKS fetch is lazy, so the bean
 * builds fine and is simply never exercised by this test).
 */
@ApplicationModuleTest
class InfraModuleTests {

	@Autowired
	private StorageService storageService;

	@Test
	void storesAndLoadsFileByRequestId() throws Exception {

		UUID id = UUID.randomUUID();
		byte[] content = "not-a-real-jpeg-but-good-enough-for-a-storage-round-trip".getBytes(StandardCharsets.UTF_8);
		MockMultipartFile file = new MockMultipartFile("files", "chest.jpg", "image/jpeg", content);

		String storagePath = storageService.store(file, id);

		Resource loaded = storageService.load(storagePath);

		assertThat(loaded.exists()).isTrue();
		assertThat(loaded.getContentAsByteArray()).isEqualTo(content);
	}
}
