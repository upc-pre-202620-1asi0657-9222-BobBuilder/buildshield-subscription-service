package pe.buildshield.subscription.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import pe.buildshield.subscription.application.ProcessedPaymentEvents;

import java.sql.Timestamp;
import java.time.Clock;

/**
 * Webhooks de la pasarela ya procesados ({@code payment_webhook_events}). Con {@code ON CONFLICT DO NOTHING}, dos
 * entregas simultáneas del mismo evento no se aplican dos veces: la segunda espera a la primera y no inserta.
 */
@Component
class JdbcProcessedPaymentEvents implements ProcessedPaymentEvents {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    JdbcProcessedPaymentEvents(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public boolean markProcessed(String eventId, String eventType) {
        return jdbc.update("""
                INSERT INTO payment_webhook_events (event_id, event_type, received_at) VALUES (?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, eventId, eventType == null ? "unknown" : eventType, Timestamp.from(clock.instant())) == 1;
    }
}
