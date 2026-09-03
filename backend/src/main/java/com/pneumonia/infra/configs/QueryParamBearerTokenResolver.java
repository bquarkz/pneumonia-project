package com.pneumonia.infra.configs;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.util.StringUtils;

/**
 * A {@link BearerTokenResolver} that falls back to the {@code access_token} query parameter
 * when no {@code Authorization} header is present.
 *
 * <p>The browser's native {@code EventSource} API cannot set arbitrary request headers, so the
 * SSE stream route ({@code GET /api/xray-requests/stream}) is the one place in this API that
 * needs to accept the bearer token as a query parameter. This resolver is deliberately
 * registered on a {@code SecurityFilterChain} scoped to only that route (see
 * {@code SecurityConfig}) - every other {@code /api/**} route keeps using the standard
 * header-only {@link DefaultBearerTokenResolver} behavior, so this is not a blanket relaxation
 * of how bearer tokens may be presented across the API.
 */
public class QueryParamBearerTokenResolver implements BearerTokenResolver {

	private static final String ACCESS_TOKEN_PARAM = "access_token";

	private final BearerTokenResolver delegate = new DefaultBearerTokenResolver();

	@Override
	public String resolve(HttpServletRequest request) {

		String headerToken = delegate.resolve(request);

		if (StringUtils.hasText(headerToken)) {
			return headerToken;
		}

		return request.getParameter(ACCESS_TOKEN_PARAM);
	}
}
