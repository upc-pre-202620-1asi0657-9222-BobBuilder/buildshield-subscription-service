package pe.buildshield.subscription.application;

import java.util.UUID;

/**
 * Evento de la pasarela ya verificado, con lo que necesita Suscripción y Pagos.
 *
 * @param eventId                identificador del evento en la pasarela ({@code evt_...}); se procesa una sola vez
 * @param providerType           tipo original ({@code checkout.session.completed}, {@code invoice.paid}, ...)
 * @param sessionId              sesión de pago ({@code cs_...}), solo en {@link Type#CHECKOUT_COMPLETED}
 * @param organizationId         organización que pagó (referencia del cliente de la sesión), si viene
 * @param customerId             cliente en la pasarela ({@code cus_...}), si viene
 * @param providerSubscriptionId suscripción en la pasarela ({@code sub_...}), si viene
 */
public record PaymentEvent(String eventId, String providerType, Type type, String sessionId, UUID organizationId,
        String customerId, String providerSubscriptionId) {

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
