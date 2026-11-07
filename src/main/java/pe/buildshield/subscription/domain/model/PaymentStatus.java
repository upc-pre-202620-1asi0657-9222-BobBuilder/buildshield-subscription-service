package pe.buildshield.subscription.domain.model;

/** Estado de un cobro: pendiente hasta que la pasarela lo confirma. {@code FAILED} queda reservado para el ER. */
public enum PaymentStatus {
    PENDING, PAID, FAILED
}
