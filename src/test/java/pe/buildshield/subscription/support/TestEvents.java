package pe.buildshield.subscription.support;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Sobres JSON de eventos de otros servicios, como los publica su outbox. */
public final class TestEvents {

    private TestEvents() {
    }

    public static String envelope(ObjectMapper json, UUID eventId, String eventType, UUID organizationId,
            String aggregateType, Object aggregateId, UUID actorId, Instant occurredAt, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", eventType);
        envelope.put("occurredAt", occurredAt.toString());
        envelope.put("organizationId", organizationId);
        envelope.put("aggregateType", aggregateType);
        envelope.put("aggregateId", aggregateId.toString());
        envelope.put("actorId", actorId);
        envelope.put("version", 1);
        envelope.put("payload", payload);
        try {
            return json.writeValueAsString(envelope);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** {@code OrganizationRegistered} de iam, con un campo adicional que el consumidor debe tolerar. */
    public static String organizationRegistered(ObjectMapper json, UUID eventId, UUID organizationId,
            Instant occurredAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", organizationId);
        payload.put("ruc", "20123456789");
        payload.put("legalName", "Constructora Andina SAC");
        payload.put("adminUserId", UUID.randomUUID());
        payload.put("adminEmail", "ana@andina.pe");
        payload.put("adminFullName", "Ana Torres");
        payload.put("campoNuevo", "se ignora");
        return envelope(json, eventId, "OrganizationRegistered", organizationId, "ORGANIZATION", organizationId,
                null, occurredAt, payload);
    }
}
