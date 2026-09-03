/**
 * X-ray request module.
 *
 * <p>Owns the entire X-ray request lifecycle end to end: batch upload intake, the
 * {@link com.pneumonia.xray.models.XrayStatus} finite state machine persisted in Postgres,
 * production of the {@link com.pneumonia.xray.messages.XrayProcessingRequested} domain event
 * (externalized to RabbitMQ via Spring Modulith event externalization), the plain Spring AMQP
 * {@code @RabbitListener} that consumes it back off the queue and drives the request through
 * to {@code DONE}/{@code FAILED} (including the retry/dead-letter branching), the manual retry
 * endpoint, the per-user Server-Sent Events status stream, and the request listing endpoint.
 *
 * <p>Laid out as {@code controllers}, {@code services}, {@code models}, {@code repositories},
 * {@code dtos}, and {@code messages} (the RabbitMQ topology, consumer, and outbound domain
 * event). All are module-internal except {@code infra}/{@code users}' own {@code services}
 * named interfaces, which this module reaches into for {@code StorageService} and
 * {@code LocalUserService}.
 */
package com.pneumonia.xray;
