package pe.buildshield.subscription.infrastructure.persistence;

import org.springframework.stereotype.Component;
import pe.buildshield.subscription.application.ProcessedPaymentEvents;
import pe.buildshield.subscription.shared.messaging.ProcessedEvents;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Webhooks de la pasarela ya procesados, en {@code processed_events} con el consumidor {@value #CONSUMER}. El id de
 * Stripe ({@code evt_...}) se convierte en un uuid determinista; V3 usa el mismo algoritmo con los ya registrados. Con
 * {@code ON CONFLICT DO NOTHING}, dos entregas simultáneas del mismo evento no se aplican dos veces.
 */
@Component
class JdbcProcessedPaymentEvents implements ProcessedPaymentEvents {

    static final String CONSUMER = "StripeWebhooks";

    private final ProcessedEvents processedEvents;

    JdbcProcessedPaymentEvents(ProcessedEvents processedEvents) {
        this.processedEvents = processedEvents;
    }

    @Override
    public boolean markProcessed(String eventId) {
        return processedEvents.markProcessed(UUID.nameUUIDFromBytes(eventId.getBytes(StandardCharsets.UTF_8)), CONSUMER);
    }
}
