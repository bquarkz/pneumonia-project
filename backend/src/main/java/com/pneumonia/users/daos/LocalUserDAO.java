package com.pneumonia.users.daos;

import com.pneumonia.infra.api.AbstractDAO;
import com.pneumonia.users.models.LocalUser;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

@Repository
public class LocalUserDAO extends AbstractDAO {

	private static final String INSERT_LOCAL_USER = """
		INSERT INTO local_user (id, email, display_name, created_at)
		VALUES (:id, :email, :displayName, :createdAt)
	""";

	private static final String FIND_BY_ID = """
		SELECT
		    id,
		    email,
		    display_name,
		    created_at
		FROM local_user
		WHERE id = :id
	""";

	private static final String COUNT_LOCAL_USER = """
		SELECT COUNT(*) FROM local_user
	""";

	private static final RowMapper<LocalUser> ROW_MAPPER = (rs, rowNum) -> new LocalUser(
			rs.getString("id"),
			rs.getString("email"),
			rs.getString("display_name"),
			rs.getTimestamp("created_at").toInstant());

	public LocalUser insert(LocalUser user) {
		final var params = new MapSqlParameterSource()
			.addValue("id", user.id())
			.addValue("email", user.email())
			.addValue("displayName", user.displayName())
			.addValue("createdAt", Timestamp.from(user.createdAt()));
		getJdbcTemplate().update(INSERT_LOCAL_USER, params);
		return user;
	}

	public Optional<LocalUser> findById(String id) {
		final var params = new MapSqlParameterSource("id", id);
		return getJdbcTemplate()
			.query(FIND_BY_ID, params, ROW_MAPPER)
			.stream()
			.findFirst();
	}

	public long count() {
		final var params = new MapSqlParameterSource();
		final var count = getJdbcTemplate()
				.queryForObject(COUNT_LOCAL_USER, params, Long.class);
		return count == null ? 0 : count;
	}
}
