package pe.buildshield.subscription.application;

import pe.buildshield.subscription.domain.model.BillingPeriod;
import pe.buildshield.subscription.domain.model.PaymentProvider;
import pe.buildshield.subscription.domain.model.Plan;

import java.util.UUID;

/**
 * Puerto de la pasarela de pagos externa (Stripe en la arquitectura v4). El cobro ocurre en la pasarela: este
 * servicio nunca recibe ni guarda datos de tarjeta, solo el identificador de la sesión de pago y su resultado.
 * Hay dos adaptadores: Stripe real y el modo simulado explícito para desarrollo local sin cuenta de Stripe.
 */
public interface PaymentGateway {

    /** Quién cobra: {@link PaymentProvider#STRIPE} o {@link PaymentProvider#SIMULATED}. */
    PaymentProvider provider();

    /**
     * Abre una sesión de pago (Stripe Checkout) para el plan y la modalidad elegidos y devuelve a dónde redirigir al
     * cliente. {@code idempotencyKey} viaja como clave de idempotencia: repetir la llamada con la misma clave no crea
     * otra sesión.
     *
     * @throws UnavailableException si la pasarela no responde, falla o no está configurada
     */
    CheckoutSession startCheckout(CheckoutRequest request);

    /**
     * Programa en la pasarela el cambio de modalidad para el siguiente periodo de cobro (US12). Si {@code target} es
     * la modalidad actual, cancela un cambio programado. La suscripción de la pasarela se ubica por la organización,
     * que viaja en sus metadatos: aquí no se guarda su identificador.
     *
     * @throws UnavailableException si la pasarela no responde, falla o no tiene la suscripción de la organización
     */
    void scheduleBillingPeriodChange(UUID organizationId, Plan plan, BillingPeriod current,
            BillingPeriod target);

    /**
     * Pedido de una sesión de pago.
     *
     * @param idempotencyKey clave de idempotencia de la llamada a la pasarela; se guarda con el cobro y vuelve en los
     *                       metadatos de la sesión. Repetirla devuelve la misma sesión con su URL.
     * @param organizationId organización que paga; viaja como referencia del cliente y en los metadatos
     */
    record CheckoutRequest(UUID idempotencyKey, UUID organizationId, Plan plan, BillingPeriod period) {
    }

    /** Sesión de pago creada en la pasarela. */
    record CheckoutSession(String sessionId, String redirectUrl) {
    }

    /** La pasarela no está disponible, falló o no está configurada. Se responde con HTTP 503. */
    class UnavailableException extends RuntimeException {

        public UnavailableException(String message) {
            super(message);
        }

        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
