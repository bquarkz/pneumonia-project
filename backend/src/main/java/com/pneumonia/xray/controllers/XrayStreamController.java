package com.pneumonia.xray.controllers;

import com.pneumonia.xray.services.SseNotifier;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-Sent Events stream of the authenticated user's own X-ray request status updates,
 * named event {@code status-update}. Secured by the SSE-specific
 * {@code SecurityFilterChain} in {@code com.pneumonia.infra.configs.SecurityConfig} (which
 * also accepts the bearer token as an {@code access_token} query parameter, since
 * {@code EventSource} cannot set an {@code Authorization} header).
 */
@RestController
@RequestMapping("/api/xray-requests")
public class XrayStreamController {

	private final SseNotifier sseNotifier;

	public XrayStreamController(SseNotifier sseNotifier) {
		this.sseNotifier = sseNotifier;
	}

	@GetMapping(path = "/stream", produces = "text/event-stream")
	public SseEmitter stream(@AuthenticationPrincipal Jwt jwt) {
		// Subject captured synchronously on the request thread, before the emitter is handed
		// off - nothing downstream (the consumer thread notifying this emitter later) ever
		// touches SecurityContextHolder.
		String userId = jwt.getSubject();
		return sseNotifier.register(userId);
	}
}
