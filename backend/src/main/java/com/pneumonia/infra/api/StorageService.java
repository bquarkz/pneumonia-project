package com.pneumonia.infra.api;

import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

/**
 * Abstraction over where and how uploaded X-ray files are persisted to disk (or, in a future
 * production strategy, to object storage). The filesystem implementation is the only one
 * provided for this MVP; callers depend only on this interface so the strategy can be swapped
 * later without touching them (DEC-0001, item 8).
 */
public interface StorageService {

	/**
	 * Persists the given file's bytes under a name derived from {@code xrayRequestId}, and
	 * returns an opaque storage path that {@link #load(String)} can later resolve back to the
	 * same content.
	 *
	 * @param file must not be {@literal null}.
	 * @param xrayRequestId must not be {@literal null}.
	 * @return the storage path to persist alongside the request row.
	 */
	String store(MultipartFile file, UUID xrayRequestId);

	/**
	 * Resolves a previously-stored file back into a readable {@link Resource}.
	 *
	 * @param storagePath as previously returned by {@link #store(MultipartFile, UUID)}.
	 */
	Resource load(String storagePath);
}
