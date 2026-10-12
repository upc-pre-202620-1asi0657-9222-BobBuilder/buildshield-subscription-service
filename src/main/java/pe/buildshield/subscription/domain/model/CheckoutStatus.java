package pe.buildshield.subscription.domain.model;

/** Estado de una sesión de pago: abierta hasta que la pasarela confirma el cobro. */
public enum CheckoutStatus {
    OPEN, COMPLETED
}
