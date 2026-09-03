package com.pneumonia.infra.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Base class for this project's hand-written JDBC repositories: holds the
 * {@link NamedParameterJdbcTemplate} shared by every module's DAOs, so persistence stays plain
 * SQL rather than an ORM.
 */
public abstract class AbstractDAO {

	@Autowired
	private NamedParameterJdbcTemplate jdbcTemplate;

	protected NamedParameterJdbcTemplate getJdbcTemplate() {
		return jdbcTemplate;
	}
}
