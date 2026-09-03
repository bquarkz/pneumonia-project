package com.pneumonia.xray.messages;

import com.pneumonia.xray.models.XrayRequest;
import java.util.UUID;
import org.springframework.modulith.events.Externalized;

/**
 * Published whenever an {@link XrayRequest} becomes ready for the ML service to process it -
 * on initial upload (RECEIVED -&gt; QUEUED) and on manual retry (FAILED -&gt; QUEUED). Both
 * callers go through the exact same {@code ApplicationEventPublisher.publishEvent(...)} call
 * (see {@code XrayRequestService}); there is deliberately no second/different way to enqueue a
 * request.
 *
 * <p>{@code @Externalized("xray.events::xray.request.created")} makes Spring Modulith durably
 * log this publication to the event publication registry (Postgres, via
 * spring-modulith-events-jpa) and then, after the enclosing transaction commits, publish it to
 * the RabbitMQ topic exchange {@code xray.events} with routing key {@code xray.request.created}
 * (spring-modulith-events-amqp) - see {@code RabbitTopologyConfig} for where that lands.
 *
 * <p>This is the OUTBOUND half only. The message is consumed back off RabbitMQ by a plain
 * {@code @RabbitListener} ({@code XrayProcessingConsumer}) - Spring Modulith's event
 * externalization has no inbound/consumption counterpart.
 */
@Externalized("xray.events::xray.request.created")
public record XrayProcessingRequested(UUID xrayRequestId) {
}
