package com.pneumonia.xray.services;

import com.pneumonia.xray.dtos.XrayRequestResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * In-process registry of open {@link SseEmitter}s, keyed by the owning user's id (the Keycloak
 * JWT {@code sub} claim). Every FSM transition - upload, consumer success, consumer
 * retry/fail, manual retry - calls {@link #notify(String, XrayRequestResponse)} to push a
 * {@code status-update} event to that user's own open connections only.
 *
 * <p>Known, accepted limitation (per PLN-0001): this registry is process-local, so it only
 * behaves correctly with exactly one backend replica - a second instance would not see events
 * produced by a consumer running in the first.
 *
 * <p>Two different threads can reach the same user's emitter list concurrently (an HTTP
 * request thread notifying after its own transaction commits, and the RabbitMQ consumer
 * thread notifying after processing a message) - {@link CopyOnWriteArrayList} makes iteration
 * during a concurrent add/remove safe without external locking.
 */
@Component
public class SseNotifier {

	private static final Logger log = LoggerFactory.getLogger(SseNotifier.class);
	private static final String EVENT_NAME = "status-update";

	private final Map<String, CopyOnWriteArrayList<SseEmitter>> emittersByUser = new ConcurrentHashMap<>();

	public SseEmitter register(String userId) {

		SseEmitter emitter = new SseEmitter(0L); // no timeout; the client reconnects on drop

		List<SseEmitter> emitters =
			emittersByUser.computeIfAbsent(userId, key -> new CopyOnWriteArrayList<>());
		emitters.add(emitter);

		Runnable remove = () -> deregister(userId, emitter);
		emitter.onCompletion(remove);
		emitter.onTimeout(remove);
		emitter.onError(throwable -> remove.run());

		return emitter;
	}

	public void notify(String userId, XrayRequestResponse payload) {

		CopyOnWriteArrayList<SseEmitter> emitters = emittersByUser.get(userId);

		if (emitters == null || emitters.isEmpty()) {
			return;
		}

		for (SseEmitter emitter : emitters) {
			try {
				emitter.send(SseEmitter.event().name(EVENT_NAME).data(payload));
			} catch (IOException | IllegalStateException e) {
				log.debug("Dropping SSE emitter for user {} after send failure: {}", userId, e.toString());
				deregister(userId, emitter);
			}
		}
	}

	private void deregister(String userId, SseEmitter emitter) {

		CopyOnWriteArrayList<SseEmitter> emitters = emittersByUser.get(userId);

		if (emitters == null) {
			return;
		}

		emitters.remove(emitter);

		if (emitters.isEmpty()) {
			emittersByUser.remove(userId, emitters);
		}
	}
}
