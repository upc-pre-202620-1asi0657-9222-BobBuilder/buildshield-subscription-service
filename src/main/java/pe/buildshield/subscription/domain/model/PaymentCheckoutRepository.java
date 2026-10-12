package pe.buildshield.subscription.domain.model;

import java.util.List;
import java.util.Optional;

/** Sesiones de pago de la organización del contexto. */
public interface PaymentCheckoutRepository {

    Optional<PaymentCheckout> findBySessionId(String sessionId);

    /** Sesiones abiertas, la más reciente primero. */
    List<PaymentCheckout> findOpen();

    PaymentCheckout save(PaymentCheckout checkout);
}
