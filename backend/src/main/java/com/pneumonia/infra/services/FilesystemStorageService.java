package com.pneumonia.infra.services;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import com.pneumonia.infra.api.StorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Filesystem-backed {@link StorageService}. Writes each X-ray under
 * {@code ${xray.storage.dir}/<xrayRequestId>.jpg}, so the storage path is entirely
 * reconstructible from the request id - the value persisted in {@code storage_path} is the
 * absolute file path, kept as an opaque string from the caller's point of view.
 */
@Component
public class FilesystemStorageService implements StorageService {

	private final Path rootDirectory;

	public FilesystemStorageService(@Value("${xray.storage.dir}") String storageDir) {
		this.rootDirectory = Path.of(storageDir);
		try {
			Files.createDirectories(rootDirectory);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not create X-ray storage directory: " + rootDirectory, e);
		}
	}

	@Override
	public String store(MultipartFile file, UUID xrayRequestId) {

		Path target = rootDirectory.resolve(xrayRequestId + ".jpg");

		try (var in = file.getInputStream()) {
			Files.copy(in, target);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not store X-ray file for request " + xrayRequestId, e);
		}

		return target.toAbsolutePath().toString();
	}

	@Override
	public Resource load(String storagePath) {
		return new FileSystemResource(Path.of(storagePath));
	}
}
