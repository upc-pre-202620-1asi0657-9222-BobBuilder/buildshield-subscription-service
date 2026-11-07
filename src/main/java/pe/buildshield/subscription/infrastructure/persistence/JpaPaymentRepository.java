package pe.buildshield.subscription.infrastructure.persistence;

import org.springframework.stereotype.Repository;
import pe.buildshield.subscription.domain.model.Payment;
import pe.buildshield.subscription.domain.model.PaymentRepository;
import pe.buildshield.subscription.domain.model.PaymentStatus;
import pe.buildshield.subscription.shared.error.ResourceNotFoundException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaPaymentRepository implements PaymentRepository {

    private final SpringDataPaymentRepository jpa;

    JpaPaymentRepository(SpringDataPaymentRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Payment> findByIdempotencyKey(UUID idempotencyKey) {
        return jpa.findByIdempotencyKey(idempotencyKey).map(PaymentJpaEntity::toDomain);
    }

    @Override
    public List<Payment> findPending() {
        return jpa.findByStatusOrderByCreatedAtDesc(PaymentStatus.PENDING).stream().map(PaymentJpaEntity::toDomain)
                .toList();
    }

    @Override
    public Payment save(Payment payment) {
        if (payment.id() == null) {
            return jpa.saveAndFlush(new PaymentJpaEntity(payment)).toDomain();
        }
        PaymentJpaEntity entity = jpa.findById(payment.id()).orElseThrow(() ->
                new ResourceNotFoundException("El cobro " + payment.id() + " no existe"));
        entity.apply(payment);
        return jpa.saveAndFlush(entity).toDomain();
    }
}
