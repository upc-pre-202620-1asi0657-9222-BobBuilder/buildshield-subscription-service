package pe.buildshield.subscription.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import pe.buildshield.subscription.domain.model.PaymentStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** El filtro multiempresa deja solo los cobros de la organización del contexto. */
interface SpringDataPaymentRepository extends JpaRepository<PaymentJpaEntity, UUID> {

    Optional<PaymentJpaEntity> findByIdempotencyKey(UUID idempotencyKey);

    List<PaymentJpaEntity> findByStatusOrderByCreatedAtDesc(PaymentStatus status);
}
