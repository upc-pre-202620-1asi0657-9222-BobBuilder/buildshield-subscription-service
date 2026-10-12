package pe.buildshield.subscription.domain.model;

/**
 * Quién cobra la suscripción pagada. {@link #STRIPE} es la pasarela real; {@link #SIMULATED} es el modo explícito de
 * desarrollo local sin cuenta de Stripe (el pago se completa desde la web y la renovación es automática).
 */
public enum PaymentProvider {
    STRIPE, SIMULATED
}
