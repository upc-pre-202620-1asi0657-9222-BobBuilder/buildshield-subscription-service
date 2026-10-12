package pe.buildshield.subscription.infrastructure.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import pe.buildshield.subscription.application.InvalidPaymentWebhookException;
import pe.buildshield.subscription.application.PaymentEvent;
import pe.buildshield.subscription.application.PaymentWebhooks;
import pe.buildshield.subscription.domain.model.PaymentCheckout;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Webhooks de Stripe: verifica {@code Stripe-Signature} y traduce los eventos que importan a la suscripción.
 *
 * <ul>
 *   <li>{@code checkout.session.completed} con {@code payment_status = paid} (o
 *       {@code checkout.session.async_payment_succeeded}): pago de la sesión, activa la suscripción. La organización
 *       viaja en {@code client_reference_id}.</li>
 *   <li>{@code invoice.paid} con {@code billing_reason = subscription_cycle}: cobro de la renovación. La suscripción de
 *       Stripe viene en {@code subscription} o, en versiones nuevas de la API, en
 *       {@code parent.subscription_details.subscription}.</li>
 *   <li>Cualquier otro: se ignora.</li>
 * </ul>
 */
@Component
class StripeWebhooks implements PaymentWebhooks {

    static final String CHECKOUT_COMPLETED = "checkout.session.completed";
    static final String ASYNC_PAYMENT_SUCCEEDED = "checkout.session.async_payment_succeeded";
    static final String INVOICE_PAID = "invoice.paid";

    private final PaymentsProperties properties;
    private final ObjectMapper json;
    private final Clock clock;

    StripeWebhooks(PaymentsProperties properties, ObjectMapper json, Clock clock) {
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public PaymentEvent verify(String payload, String signatureHeader) {
        StripeSignature.verify(payload, signatureHeader, properties.effectiveWebhookSecret(),
                properties.stripe().webhookTolerance(), clock.instant());
        JsonNode event = read(payload);
        String eventId = text(event, "id");
        String type = text(event, "type");
        JsonNode object = event.path("data").path("object");
        if (eventId == null || type == null || !object.isObject()) {
            throw new InvalidPaymentWebhookException("El webhook no es un evento de Stripe");
        }
        return switch (type) {
            case CHECKOUT_COMPLETED, ASYNC_PAYMENT_SUCCEEDED -> checkout(eventId, type, object);
            case INVOICE_PAID -> invoice(eventId, type, object);
            default -> ignored(eventId, type);
        };
    }

    private PaymentEvent checkout(String eventId, String type, JsonNode session) {
        String paymentStatus = text(session, "payment_status");
        if (CHECKOUT_COMPLETED.equals(type) && paymentStatus != null && !paymentStatus.equals("paid")
                && !paymentStatus.equals("no_payment_required")) {
            // Medio de pago diferido: se activa cuando llegue checkout.session.async_payment_succeeded.
            return ignored(eventId, type);
        }
        String reference = text(session, "client_reference_id");
        if (reference == null) {
            reference = text(session.path("metadata"), "organizationId");
        }
        return new PaymentEvent(eventId, type, PaymentEvent.Type.CHECKOUT_COMPLETED, text(session, "id"),
                uuid(reference), id(session, "customer"), id(session, "subscription"));
    }

    private PaymentEvent invoice(String eventId, String type, JsonNode invoice) {
        if (!"subscription_cycle".equals(text(invoice, "billing_reason"))) {
            // El primer cobro (subscription_create) ya activó la suscripción con checkout.session.completed.
            return ignored(eventId, type);
        }
        String subscription = id(invoice, "subscription");
        if (subscription == null) {
            subscription = text(invoice.path("parent").path("subscription_details"), "subscription");
        }
        return new PaymentEvent(eventId, type, PaymentEvent.Type.RENEWAL_PAID, null, null, id(invoice, "customer"),
                subscription);
    }

    private static PaymentEvent ignored(String eventId, String type) {
        return new PaymentEvent(eventId, type, PaymentEvent.Type.IGNORED, null, null, null, null);
    }

    @Override
    public SignedWebhook simulateCheckoutCompleted(PaymentCheckout checkout, Instant paidAt) {
        String suffix = checkout.idempotencyKey().toString().replace("-", "");
        ObjectNode session = json.createObjectNode()
                .put("id", checkout.sessionId())
                .put("object", "checkout.session")
                .put("mode", "subscription")
                .put("payment_status", "paid")
                .put("client_reference_id", checkout.organizationId().toString())
                .put("customer", "cus_sim_" + suffix)
                .put("subscription", "sub_sim_" + suffix);
        session.putObject("metadata")
                .put("organizationId", checkout.organizationId().toString())
                .put("plan", checkout.plan().name())
                .put("billingPeriod", checkout.billingPeriod().name());
        ObjectNode event = json.createObjectNode()
                .put("id", "evt_sim_" + UUID.randomUUID().toString().replace("-", ""))
                .put("object", "event")
                .put("type", CHECKOUT_COMPLETED)
                .put("created", paidAt.getEpochSecond())
                .put("livemode", false);
        event.putObject("data").set("object", session);
        String payload = event.toString();
        return new SignedWebhook(payload, StripeSignature.header(payload, properties.effectiveWebhookSecret(),
                clock.instant()));
    }

    private JsonNode read(String payload) {
        try {
            return json.readTree(payload);
        } catch (JsonProcessingException ex) {
            throw new InvalidPaymentWebhookException("El cuerpo del webhook no es JSON");
        }
    }

    /** Campo de texto, o nulo si falta. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    /** Referencia a otro objeto de Stripe: el id como texto o el objeto expandido con su {@code id}. */
    private static String id(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isObject() ? text(value, "id") : text(node, field);
    }

    private static UUID uuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
