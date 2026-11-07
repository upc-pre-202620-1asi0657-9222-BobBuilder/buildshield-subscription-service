package pe.buildshield.subscription.domain.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Cobros de la organización del contexto. */
public interface PaymentRepository {

    Optional<Payment> findByIdempotencyKey(UUID idempotencyKey);

    /** Cobros pendientes, el más reciente primero. */
    List<Payment> findPending();

    Payment save(Payment payment);
}
