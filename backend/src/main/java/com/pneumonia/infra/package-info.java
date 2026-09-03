/**
 * Cross-cutting infrastructure module.
 *
 * <p>Owns environment-facing concerns that the other two modules depend on but that are not
 * themselves part of either module's domain: the {@code StorageService} abstraction (and its
 * filesystem implementation, under {@code services}), web security configuration (OAuth2
 * resource server setup, including the query-parameter bearer token resolver needed for the
 * SSE stream route, under {@code security}), and {@code AbstractDAO}, the shared
 * {@code NamedParameterJdbcTemplate} base every module's own hand-written JDBC repositories
 * extend (that template itself provided by {@code JdbcTemplateConfig}, under {@code configs}).
 *
 * <p>{@code api} is declared a {@code @NamedInterface} so {@code xray} and {@code users} - the
 * only other modules that reach into it, for {@code StorageService} and {@code AbstractDAO}
 * respectively - can keep depending on it despite it not being this module's root package.
 *
 * <p>Deliberately left without an explicit {@code @ApplicationModule} declaration (and
 * therefore without an {@code allowedDependencies} restriction): this is a shared/support
 * module that other modules are expected to depend on freely, so Spring Modulith's default,
 * permissive module-boundary detection (by package location under the application's root
 * package) is exactly what is wanted here.
 */
package com.pneumonia.infra;
