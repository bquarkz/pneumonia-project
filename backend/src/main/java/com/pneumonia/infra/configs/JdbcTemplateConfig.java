package com.pneumonia.infra.configs;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Provides the {@link NamedParameterJdbcTemplate} that {@code AbstractDAO} hands to every
 * module's own JDBC repositories, so multi-value conditions (the FSM transition queries' status
 * {@code IN (...)} lists) bind as a single named collection parameter instead of one {@code ?}
 * per element built up by hand.
 */
@Configuration
public class JdbcTemplateConfig {

	@Bean
	public NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource dataSource) {
		return new NamedParameterJdbcTemplate(dataSource);
	}
}
