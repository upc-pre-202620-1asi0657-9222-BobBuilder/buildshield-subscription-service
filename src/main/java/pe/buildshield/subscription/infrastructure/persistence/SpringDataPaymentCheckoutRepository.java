package pe.buildshield.subscription.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import pe.buildshield.subscription.domain.model.CheckoutStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** El filtro multiempresa deja solo las sesiones de la organización del contexto. */
interface SpringDataPaymentCheckoutRepository extends JpaRepository<PaymentCheckoutJpaEntity, UUID> {

    Optional<PaymentCheckoutJpaEntity> findBySessionId(String sessionId);

    List<PaymentCheckoutJpaEntity> findByStatusOrderByOpenedAtDesc(CheckoutStatus status);
}
