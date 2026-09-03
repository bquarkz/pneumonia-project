package com.pneumonia.infra.configs;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * OAuth2 resource server configuration.
 *
 * <p>Every {@code /api/**} route requires a valid Keycloak-issued bearer JWT. JWKS is fetched
 * from Keycloak over the docker-compose network ({@code keycloak.issuer-uri}), but the token's
 * {@code iss} claim is validated against the browser-facing URL instead ({@code
 * keycloak.public-issuer-uri}) - see the {@link #jwtDecoder()} javadoc for why these differ.
 * {@code /actuator/health} is left open for the Docker healthcheck.
 *
 * <p>Three ordered filter chains:
 * <ol>
 *   <li>The SSE stream route alone, using {@link QueryParamBearerTokenResolver} so the token
 *       can travel as a query parameter (the browser's {@code EventSource} cannot set an
 *       {@code Authorization} header) - see class javadoc there for why this is scoped this
 *       narrowly.</li>
 *   <li>The rest of {@code /api/**}, using the standard header-only bearer resolver.</li>
 *   <li>Everything else, permitting only the actuator health endpoint and otherwise requiring
 *       authentication (defense in depth - no route is unauthenticated by omission).</li>
 * </ol>
 *
 * <p>CORS is configured explicitly for the frontend's own origin ({@code BACKEND_PUBLIC_URL}'s
 * counterpart, {@code http://localhost:4200} per the fixed docker-compose contract) since the
 * Angular app calls this backend directly, cross-origin, from the browser - including for the
 * SSE route, which still needs {@code Access-Control-Allow-Origin} even though
 * {@code EventSource} itself cannot attach custom headers.
 */
@Configuration
public class SecurityConfig {

	private static final String SSE_STREAM_PATTERN = "/api/xray-requests/stream";

	@Value("${cors.allowed-origin}")
	private String allowedOrigin;

	@Value("${keycloak.issuer-uri}")
	private String keycloakIssuerUri;

	@Value("${keycloak.public-issuer-uri}")
	private String keycloakPublicIssuerUri;

	@Bean
	@Order(1)
	public SecurityFilterChain sseFilterChain(HttpSecurity http) throws Exception {

		http.securityMatcher(SSE_STREAM_PATTERN)
			.cors(Customizer.withDefaults())
			.csrf(AbstractHttpConfigurer::disable)
			.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
			.oauth2ResourceServer(oauth2 -> oauth2
				.bearerTokenResolver(new QueryParamBearerTokenResolver())
				.jwt(Customizer.withDefaults()));

		return http.build();
	}

	@Bean
	@Order(2)
	public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {

		http.securityMatcher("/api/**")
			.cors(Customizer.withDefaults())
			.csrf(AbstractHttpConfigurer::disable)
			.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
			.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));

		return http.build();
	}

	@Bean
	@Order(3)
	public SecurityFilterChain defaultFilterChain(HttpSecurity http) throws Exception {

		http.cors(Customizer.withDefaults())
			.csrf(AbstractHttpConfigurer::disable)
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
				.anyRequest().authenticated());

		return http.build();
	}

	/**
	 * Keycloak dev-mode echoes each request's {@code Host} header into the token's {@code iss}
	 * claim, so a token obtained by the browser (via {@code KEYCLOAK_PUBLIC_URL}) carries a
	 * different issuer than what the backend would compute by hitting Keycloak over the
	 * docker-compose network ({@code KEYCLOAK_ISSUER_URI}) - Spring Boot's {@code issuer-uri}
	 * auto-configuration conflates "where to fetch JWKS from" with "expected iss claim", which
	 * cannot represent this split. This decoder fetches JWKS internally but validates {@code iss}
	 * against the public URL instead.
	 */
	@Bean
	public JwtDecoder jwtDecoder() {

		NimbusJwtDecoder decoder = NimbusJwtDecoder
			.withJwkSetUri(keycloakIssuerUri + "/protocol/openid-connect/certs")
			.build();
		decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(keycloakPublicIssuerUri));

		return decoder;
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource() {

		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(List.of(allowedOrigin));
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
		configuration.setAllowedHeaders(List.of("*"));
		configuration.setAllowCredentials(true);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);

		return source;
	}
}
