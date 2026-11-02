package pe.buildshield.subscription.application;

import pe.buildshield.subscription.domain.model.BillingPeriod;
import pe.buildshield.subscription.domain.model.PlanCode;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Evento de la pasarela ya verificado, con lo que necesita Suscripción y Pagos.
 *
 * @param eventId        identificador del evento en la pasarela ({@code evt_...}); se procesa una sola vez
 * @param providerType   tipo original ({@code checkout.session.completed}, {@code invoice.paid}, ...)
 * @param sessionId      sesión de pago ({@code cs_...}), solo en {@link Type#CHECKOUT_COMPLETED}
 * @param organizationId organización que pagó (referencia del cliente o metadatos firmados), si viene
 * @param plan           plan pagado (metadatos de la sesión), si viene
 * @param billingPeriod  modalidad pagada (metadatos de la sesión), si viene
 * @param paymentKey     clave de idempotencia del cobro (metadatos de la sesión), si viene
 * @param chargeId       identificador del cobro en la pasarela (factura {@code in_...}), si viene
 * @param amount         monto cobrado, solo en {@link Type#RENEWAL_PAID} y si viene
 * @param gatewaySubscriptionId suscripción en la pasarela ({@code sub_...}), si viene
 */
public record PaymentEvent(String eventId, String providerType, Type type, String sessionId, UUID organizationId,
        PlanCode plan, BillingPeriod billingPeriod, UUID paymentKey, String chargeId, BigDecimal amount,
        String gatewaySubscriptionId) {

    /** Evento sin efecto sobre la suscripción. */
    public static PaymentEvent ignored(String eventId, String providerType) {
        return new PaymentEvent(eventId, providerType, Type.IGNORED, null, null, null, null, null, null, null,
                null);
    }

    /** Pago de una sesión de Checkout. */
    public static PaymentEvent checkoutCompleted(String eventId, String providerType, String sessionId,
            UUID organizationId, PlanCode plan, BillingPeriod billingPeriod, UUID paymentKey, String chargeId,
            String gatewaySubscriptionId) {
        return new PaymentEvent(eventId, providerType, Type.CHECKOUT_COMPLETED, sessionId, organizationId, plan,
                billingPeriod, paymentKey, chargeId, null, gatewaySubscriptionId);
    }

    /** Cobro de la renovación de un periodo. */
    public static PaymentEvent renewalPaid(String eventId, String providerType, UUID organizationId, String chargeId,
            BigDecimal amount, String gatewaySubscriptionId) {
        return new PaymentEvent(eventId, providerType, Type.RENEWAL_PAID, null, organizationId, null, null, null,
                chargeId, amount, gatewaySubscriptionId);
    }

    /** Lo que significa el evento para la suscripción. */
    public enum Type {
        /** El cliente pagó la sesión de Checkout: se activa la suscripción. */
        CHECKOUT_COMPLETED,
        /** Se cobró la renovación de un periodo: empieza el siguiente periodo. */
        RENEWAL_PAID,
        /** Cualquier otro evento: se registra y se ignora. */
        IGNORED
    }
}
