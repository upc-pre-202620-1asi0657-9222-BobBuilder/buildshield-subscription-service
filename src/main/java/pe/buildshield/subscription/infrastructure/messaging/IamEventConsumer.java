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
 * Consume {@code buildshield.iam}: {@code OrganizationRegistered} inicia la prueba gratuita de la organización
 * (US08). Cada evento se procesa una sola vez; los demás tipos se ignoran.
 */
@Component
public class IamEventConsumer {

    static final String ORGANIZATION_REGISTERED = "OrganizationRegistered";

    private static final Logger log = LoggerFactory.getLogger(IamEventConsumer.class);

    private final IdempotentEventProcessor processor;
    private final SubscriptionCommandService commands;

    public IamEventConsumer(IdempotentEventProcessor processor, SubscriptionCommandService commands) {
        this.processor = processor;
        this.commands = commands;
    }

    @KafkaListener(topics = "${buildshield.messaging.consumes.iam:buildshield.iam}")
    public void onMessage(ConsumerRecord<String, String> record) {
        TenantScopedEvents.process(processor, record, this::handle);
    }

    void handle(EventEnvelope event) {
        if (ORGANIZATION_REGISTERED.equals(event.eventType())) {
            commands.startTrial(event.occurredAt());
        } else {
            log.debug("Evento {} ignorado por subscription", event.eventType());
        }
    }
}
