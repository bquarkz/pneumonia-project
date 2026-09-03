package com.pneumonia.xray.daos;

import com.pneumonia.infra.api.AbstractDAO;
import com.pneumonia.xray.models.XrayRequest;
import com.pneumonia.xray.models.XrayStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

@Repository
public class XrayRequestDAO extends AbstractDAO {

	private static final String INSERT_XRAY_REQUEST = """
		INSERT INTO xray_request (id,
		                          user_id,
		                          original_filename,
		                          storage_path,
		                          status,
		                          retry_count,
		                          result_pneumonia,
		                          result_confidence,
		                          model_version,
		                          created_at,
		                          updated_at,
		                          processed_at)
		VALUES (:id,
		        :userId,
		        :originalFilename,
		        :storagePath,
		        :status,
		        :retryCount,
		        :resultPneumonia,
		        :resultConfidence,
		        :modelVersion,
		        :createdAt,
		        :updatedAt,
		        :processedAt)
	""";

	private static final String FIND_BY_ID = """
		SELECT
		    id,
		    user_id,
		    original_filename,
		    storage_path,
		    status,
		    retry_count,
		    result_pneumonia,
		    result_confidence,
		    model_version,
		    created_at,
		    updated_at,
		    processed_at
		FROM xray_request
		WHERE id = :id
	""";

	private static final String FIND_BY_USER_ID_ORDER_BY_CREATED_AT_DESC = """
		SELECT
		    id,
		    user_id,
		    original_filename,
		    storage_path,
		    status,
		    retry_count,
		    result_pneumonia,
		    result_confidence,
		    model_version,
		    created_at,
		    updated_at,
		    processed_at
		FROM xray_request
		WHERE user_id = :userId
		ORDER BY created_at DESC
	""";

	private static final String FIND_BY_ID_AND_USER_ID = """
		SELECT
		    id,
		    user_id,
		    original_filename,
		    storage_path,
		    status,
		    retry_count,
		    result_pneumonia,
		    result_confidence,
		    model_version,
		    created_at,
		    updated_at,
		    processed_at
		FROM xray_request
		WHERE id = :id AND user_id = :userId
	""";

	private static final String TRANSITION_STATUS = """
		UPDATE xray_request
		   SET status = :to, updated_at = :updatedAt
		 WHERE id = :id AND status IN (:from)
	""";

	private static final String TRANSITION_STATUS_WITH_RETRY_COUNT = """
		UPDATE xray_request SET status = :to,
		                        retry_count = :retryCount,
		                        updated_at = :updatedAt
		 WHERE id = :id AND status IN (:from)
	""";

	private static final String APPLY_RESULT = """
		UPDATE xray_request SET result_pneumonia = :pneumonia,
		                        result_confidence = :confidence,
		                        model_version = :modelVersion,
		                        processed_at = :processedAt,
		                        updated_at = :processedAt
		 WHERE id = :id
	""";

	private static final RowMapper<XrayRequest> ROW_MAPPER = (rs, rowNum) -> new XrayRequest(
			rs.getObject("id", UUID.class),
			rs.getString("user_id"),
			rs.getString("original_filename"),
			rs.getString("storage_path"),
			XrayStatus.valueOf(rs.getString("status")),
			rs.getInt("retry_count"),
			rs.getObject("result_pneumonia", Boolean.class),
			rs.getObject("result_confidence", Double.class),
			rs.getString("model_version"),
			rs.getTimestamp("created_at").toInstant(),
			rs.getTimestamp("updated_at").toInstant(),
			rs.getTimestamp("processed_at") == null ? null : rs.getTimestamp("processed_at").toInstant());

	public XrayRequest insert(XrayRequest request) {
		final var params = new MapSqlParameterSource()
			.addValue("id", request.id())
			.addValue("userId", request.userId())
			.addValue("originalFilename", request.originalFilename())
			.addValue("storagePath", request.storagePath())
			.addValue("status", request.status().name())
			.addValue("retryCount", request.retryCount())
			.addValue("resultPneumonia", request.resultPneumonia())
			.addValue("resultConfidence", request.resultConfidence())
			.addValue("modelVersion", request.modelVersion())
			.addValue("createdAt", Timestamp.from(request.createdAt()))
			.addValue("updatedAt", Timestamp.from(request.updatedAt()))
			.addValue("processedAt", request.processedAt() == null ? null : Timestamp.from(request.processedAt()));
		getJdbcTemplate().update(INSERT_XRAY_REQUEST, params);
		return request;
	}

	public Optional<XrayRequest> findById(UUID id) {
		final var params = new MapSqlParameterSource("id", id);
		return getJdbcTemplate()
			.query(FIND_BY_ID, params, ROW_MAPPER)
			.stream()
			.findFirst();
	}

	public List<XrayRequest> findByUserIdOrderByCreatedAtDesc(String userId) {
		final var params = new MapSqlParameterSource("userId", userId);
		return getJdbcTemplate().query(FIND_BY_USER_ID_ORDER_BY_CREATED_AT_DESC, params, ROW_MAPPER);
	}

	public Optional<XrayRequest> findByIdAndUserId(UUID id, String userId) {
		final var params = new MapSqlParameterSource().addValue("id", id).addValue("userId", userId);
		return getJdbcTemplate()
			.query(FIND_BY_ID_AND_USER_ID, params, ROW_MAPPER)
			.stream()
			.findFirst();
	}

	/**
	 * Conditionally transitions {@code status}, leaving {@code retryCount} untouched. Returns
	 * the number of affected rows: 0 means the row's current status was not in {@code from} -
	 * i.e. some other trigger already owns/handled this request - and callers SHALL treat that
	 * as a no-op, not an error.
	 */
	public int transitionStatus(UUID id, XrayStatus to, Collection<XrayStatus> from, Instant now) {
		final var params = new MapSqlParameterSource()
			.addValue("to", to.name())
			.addValue("updatedAt", Timestamp.from(now))
			.addValue("id", id)
			.addValue("from", statusNames(from));
		return getJdbcTemplate().update(TRANSITION_STATUS, params);
	}

	/**
	 * Same conditional-transition guarantee as {@link #transitionStatus}, additionally setting
	 * {@code retryCount} atomically with the status change (used for the RETRYING/FAILED
	 * branch, where the new retry count and the new status must land together, and for the
	 * manual-retry endpoint, which resets the count to 0 as part of the FAILED -&gt; QUEUED
	 * transition).
	 */
	public int transitionStatusWithRetryCount(UUID id, XrayStatus to, int retryCount, Collection<XrayStatus> from, Instant now) {
		final var params = new MapSqlParameterSource()
			.addValue("to", to.name())
			.addValue("retryCount", retryCount)
			.addValue("updatedAt", Timestamp.from(now))
			.addValue("id", id)
			.addValue("from", statusNames(from));
		return getJdbcTemplate().update(TRANSITION_STATUS_WITH_RETRY_COUNT, params);
	}

	/**
	 * Records a successful ML prediction's result columns and marks the request processed.
	 * Unconditional, like the JPA-era in-memory mutation it replaces: the caller has already
	 * gated the status transition to {@code DONE} via {@link #transitionStatus}.
	 */
	public int applyResult(UUID id, boolean pneumonia, double confidence, String modelVersion, Instant processedAt) {
		final var params = new MapSqlParameterSource()
			.addValue("pneumonia", pneumonia)
			.addValue("confidence", confidence)
			.addValue("modelVersion", modelVersion)
			.addValue("processedAt", Timestamp.from(processedAt))
			.addValue("id", id);
		return getJdbcTemplate().update(APPLY_RESULT, params);
	}

	private static List<String> statusNames(Collection<XrayStatus> statuses) {
		return statuses.stream().map(Enum::name).toList();
	}
}
