package com.pneumonia.xray.messages;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Declares the RabbitMQ topology as {@code Declarable} beans, so Spring AMQP's auto-configured
 * {@code RabbitAdmin} (re)creates it idempotently on every backend startup.
 *
 * <ul>
 *   <li>{@code xray.events} - topic exchange. Spring Modulith's AMQP event externalization
 *       publishes {@link XrayProcessingRequested} here (see that class' {@code @Externalized}
 *       value) after the enclosing transaction commits.</li>
 *   <li>{@code xray.processing.queue} - durable, bound to {@code xray.events} with routing key
 *       {@code xray.request.created}. Consumed by {@link XrayProcessingConsumer}.</li>
 *   <li>{@code xray.processing.retry} - durable, deliberately NOT bound to {@code xray.events}
 *       (only reachable by an explicit publish, from {@link XrayFailureRouter}). TTL 15s with
 *       its dead-letter target pointed at the default exchange + {@code xray.processing.queue}
 *       as routing key, so an expired message reappears directly on the main queue - this is
 *       the ONLY broker-managed dead-lettering in this topology, and it is unrelated to the
 *       retry-vs-DLQ application-level decision in {@link XrayFailureRouter}.</li>
 *   <li>{@code xray.processing.dlq} - durable, plain, terminal. Only reachable by an explicit
 *       publish (also from {@link XrayFailureRouter}), never by broker-level dead-lettering.
 *       No listener - a human inspects it.</li>
 * </ul>
 */
@Configuration
class RabbitTopologyConfig {

	static final String EXCHANGE = "xray.events";
	static final String ROUTING_KEY = "xray.request.created";

	static final String MAIN_QUEUE = "xray.processing.queue";
	static final String RETRY_QUEUE = "xray.processing.retry";
	static final String DEAD_LETTER_QUEUE = "xray.processing.dlq";

	@Bean
	TopicExchange xrayEventsExchange() {
		return new TopicExchange(EXCHANGE, true, false);
	}

	@Bean
	Queue xrayProcessingQueue() {
		return QueueBuilder.durable(MAIN_QUEUE).build();
	}

	@Bean
	Binding xrayProcessingQueueBinding(Queue xrayProcessingQueue, TopicExchange xrayEventsExchange) {
		return BindingBuilder.bind(xrayProcessingQueue).to(xrayEventsExchange).with(ROUTING_KEY);
	}

	@Bean
	Queue xrayProcessingRetryQueue() {
		return QueueBuilder.durable(RETRY_QUEUE)
			.withArgument("x-message-ttl", 15000)
			.withArgument("x-dead-letter-exchange", "")
			.withArgument("x-dead-letter-routing-key", MAIN_QUEUE)
			.build();
	}

	@Bean
	Queue xrayProcessingDlq() {
		return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
	}

	/**
	 * Spring Boot does not auto-configure a JSON {@link MessageConverter} for RabbitMQ - the
	 * auto-configured {@code RabbitTemplate}/listener container factory default to
	 * {@code SimpleMessageConverter} unless a {@code MessageConverter} bean is present, in
	 * which case Boot wires that single bean into both. Declaring it explicitly here (rather
	 * than relying on spring-modulith-events-amqp's own {@code RabbitTemplateCustomizer},
	 * which only reaches the {@code RabbitTemplate} used for outbound event externalization)
	 * ensures {@link XrayProcessingConsumer}'s {@code @RabbitListener} - and
	 * {@link XrayFailureRouter}'s own {@code RabbitTemplate.convertAndSend} calls to the retry
	 * and dead-letter queues - all read and write the exact same JSON shape as the messages
	 * Modulith externalizes on initial upload/manual retry.
	 */
	@Bean
	MessageConverter rabbitMessageConverter(JsonMapper jsonMapper) {
		return new JacksonJsonMessageConverter(jsonMapper);
	}
}
