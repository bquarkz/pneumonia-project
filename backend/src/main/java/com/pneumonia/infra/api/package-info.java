/**
 * {@code infra}'s public contract: {@code StorageService} (and its filesystem implementation),
 * which {@code xray} depends on directly, and {@code AbstractDAO} - the shared
 * {@code NamedParameterJdbcTemplate} base every module's own JDBC repositories extend, used by
 * both {@code xray} and {@code users}.
 */
@org.springframework.modulith.NamedInterface
package com.pneumonia.infra.api;
