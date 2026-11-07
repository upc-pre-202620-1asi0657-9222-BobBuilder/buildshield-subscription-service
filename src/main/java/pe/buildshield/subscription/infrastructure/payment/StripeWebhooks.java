package pe.buildshield.subscription.infrastructure.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import pe.buildshield.subscription.application.InvalidPaymentWebhookException;
import pe.buildshield.subscription.application.PaymentEvent;
import pe.buildshield.subscription.application.PaymentWebhooks;
import pe.buildshield.subscription.application.SimulatedSession;
import pe.buildshield.subscription.domain.model.BillingPeriod;
import pe.buildshield.subscription.domain.model.PlanCode;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Webhooks de Stripe: verifica {@code Stripe-Signature} y traduce los eventos que importan a la suscripción.
 *
 * <ul>
 *   <li>{@code checkout.session.completed} con {@code payment_status = paid} (o
 *       {@code checkout.session.async_payment_succeeded}): pago de la sesión, activa la suscripción. La organización
 *       viaja en {@code client_reference_id}; el plan, la modalidad y la clave del cobro, en los metadatos que puso
 *       este servicio al abrir la sesión (la firma garantiza que no se alteraron). La suscripción de Stripe viene en
 *       {@code subscription}.</li>
 *   <li>{@code invoice.paid} con {@code billing_reason = subscription_cycle}: cobro de la renovación. La organización
 *       viene en los metadatos de la suscripción, copiados en {@code subscription_details.metadata} o, en versiones
 *       nuevas de la API, en {@code parent.subscription_details.metadata}. La suscripción de Stripe viene en
 *       {@code subscription} o, en versiones nuevas, en {@code parent.subscription_details.subscription}.</li>
 *   <li>{@code invoice.payment_failed}: fallo de cobro o rechazo de tarjeta. Extrae la organización, la factura, el
 *       monto y el motivo de fallo para actualizar el cobro a {@code FAILED} y emitir {@code PaymentFailed}.</li>
 *   <li>Cualquier otro: se ignora.</li>
 * </ul>
 */
@Component
class StripeWebhooks implements PaymentWebhooks {

    static final String CHECKOUT_COMPLETED = "checkout.session.completed";
    static final String ASYNC_PAYMENT_SUCCEEDED = "checkout.session.async_payment_succeeded";
    static final String INVOICE_PAID = "invoice.paid";
    static final String INVOICE_PAYMENT_FAILED = "invoice.payment_failed";

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
            case INVOICE_PAYMENT_FAILED -> invoiceFailed(eventId, type, object);
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
        JsonNode metadata = session.path("metadata");
        return PaymentEvent.checkoutCompleted(eventId, type, text(session, "id"), uuid(reference),
                enumValue(PlanCode.class, text(metadata, "plan")),
                enumValue(BillingPeriod.class, text(metadata, "billingPeriod")), uuid(text(metadata, "paymentKey")),
                id(session, "invoice"), id(session, "subscription"));
    }

    private PaymentEvent invoice(String eventId, String type, JsonNode invoice) {
        if (!"subscription_cycle".equals(text(invoice, "billing_reason"))) {
            // El primer cobro (subscription_create) ya activó la suscripción con checkout.session.completed.
            return ignored(eventId, type);
        }
        String organization = extractOrganization(invoice);
        String subscription = extractSubscription(invoice);
        JsonNode amountPaid = invoice.path("amount_paid");
        BigDecimal amount = amountPaid.isIntegralNumber() ? amountPaid.decimalValue().movePointLeft(2) : null;
        return PaymentEvent.renewalPaid(eventId, type, uuid(organization), text(invoice, "id"), amount,
                subscription);
    }

    private PaymentEvent invoiceFailed(String eventId, String type, JsonNode invoice) {
        String organization = extractOrganization(invoice);
        String subscription = extractSubscription(invoice);
        String key = text(invoice.path("metadata"), "paymentKey");
        if (key == null) {
            key = text(invoice.path("subscription_details").path("metadata"), "paymentKey");
        }
        if (key == null) {
            key = text(invoice.path("parent").path("subscription_details").path("metadata"), "paymentKey");
        }
        UUID paymentKey = uuid(key);
        String chargeId = text(invoice, "id");
        JsonNode amountDue = invoice.path("amount_due");
        if (!amountDue.isIntegralNumber()) {
            amountDue = invoice.path("amount_paid");
        }
        BigDecimal amount = amountDue.isIntegralNumber() ? amountDue.decimalValue().movePointLeft(2) : null;

        String failureReason = text(invoice.path("last_finalization_error"), "message");
        if (failureReason == null) {
            failureReason = text(invoice, "billing_reason");
        }
        if (failureReason == null) {
            failureReason = "Payment failed";
        }
        return PaymentEvent.paymentFailed(eventId, type, uuid(organization), paymentKey, chargeId, amount,
                failureReason, subscription);
    }

    private static String extractOrganization(JsonNode invoice) {
        String org = text(invoice.path("subscription_details").path("metadata"), "organizationId");
        if (org == null) {
            org = text(invoice.path("parent").path("subscription_details").path("metadata"), "organizationId");
        }
        if (org == null) {
            org = text(invoice.path("metadata"), "organizationId");
        }
        return org;
    }

    private static String extractSubscription(JsonNode invoice) {
        String subscription = id(invoice, "subscription");
        if (subscription == null) {
            subscription = id(invoice.path("parent").path("subscription_details"), "subscription");
        }
        return subscription;
    }

    private static PaymentEvent ignored(String eventId, String type) {
        return PaymentEvent.ignored(eventId, type);
    }

    @Override
    public SignedWebhook simulateCheckoutCompleted(SimulatedSession simulated, UUID organizationId, Instant paidAt) {
        String suffix = simulated.paymentKey().toString().replace("-", "");
        ObjectNode session = json.createObjectNode()
                .put("id", simulated.value())
                .put("object", "checkout.session")
                .put("mode", "subscription")
                .put("payment_status", "paid")
                .put("client_reference_id", organizationId.toString())
                .put("invoice", "in_sim_" + suffix)
                .put("subscription", "sub_sim_" + suffix);
        session.putObject("metadata")
                .put("organizationId", organizationId.toString())
                .put("plan", simulated.plan().name())
                .put("billingPeriod", simulated.period().name())
                .put("paymentKey", simulated.paymentKey().toString());
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

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try {
            return value == null ? null : Enum.valueOf(type, value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static UUID uuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
