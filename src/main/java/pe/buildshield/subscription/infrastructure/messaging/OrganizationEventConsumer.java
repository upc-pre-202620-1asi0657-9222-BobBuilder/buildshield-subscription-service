package pe.buildshield.subscription.infrastructure.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import pe.buildshield.subscription.application.SubscriptionCommandService;
import pe.buildshield.subscription.shared.messaging.EventEnvelope;
import pe.buildshield.subscription.shared.messaging.IdempotentEventProcessor;

/**
 * Consume {@code buildshield.iam} (TAREA.md línea 76): {@code OrganizationRegistered} inicia la prueba gratuita
 * de la organización (US08). Cada evento se procesa una sola vez con clave de idempotencia {@code OrganizationEventConsumer}
 * en {@code processed_events}; los demás tipos se ignoran.
 */
@Component
public class OrganizationEventConsumer {

    static final String ORGANIZATION_REGISTERED = "OrganizationRegistered";
    static final String CONSUMER = "OrganizationEventConsumer";

    private static final Logger log = LoggerFactory.getLogger(OrganizationEventConsumer.class);

    private final IdempotentEventProcessor processor;
    private final SubscriptionCommandService commands;

    public OrganizationEventConsumer(IdempotentEventProcessor processor, SubscriptionCommandService commands) {
        this.processor = processor;
        this.commands = commands;
    }

    @KafkaListener(topics = "${buildshield.messaging.consumes.iam:buildshield.iam}")
    public void onMessage(ConsumerRecord<String, String> record) {
        processor.process(CONSUMER, record, this::handle);
    }

    void handle(EventEnvelope event) {
        if (ORGANIZATION_REGISTERED.equals(event.eventType())) {
            commands.startTrial(event.occurredAt());
        } else {
            log.debug("Evento {} ignorado por subscription", event.eventType());
        }
    }
}
