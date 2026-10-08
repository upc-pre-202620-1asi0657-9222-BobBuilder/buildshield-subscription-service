package pe.buildshield.subscription.application;

import pe.buildshield.subscription.domain.model.BillingPeriod;
import pe.buildshield.subscription.domain.model.Plan;

import java.util.UUID;

/**
 * Puerto de la pasarela de pagos externa (Stripe en la arquitectura v4). El cobro ocurre en la pasarela: este
 * servicio nunca recibe ni guarda datos de tarjeta, solo el identificador de la sesión de pago y su resultado.
 * La implementación real llega en el Sprint 4 (US10 y US11).
 */
public interface PaymentGateway {

    /** Abre una sesión de pago para el plan y la modalidad elegidos y devuelve a dónde redirigir al cliente. */
    CheckoutSession startCheckout(UUID organizationId, Plan plan, BillingPeriod period);

    /** Sesión de pago creada en la pasarela. */
    record CheckoutSession(String sessionId, String redirectUrl) {
    }

    /** La pasarela no está disponible o no está configurada. */
    class UnavailableException extends RuntimeException {

        public UnavailableException(String message) {
            super(message);
        }
    }
}
