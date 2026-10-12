package pe.buildshield.subscription.infrastructure.persistence;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import pe.buildshield.subscription.domain.model.CheckoutStatus;
import pe.buildshield.subscription.domain.model.PaymentCheckout;
import pe.buildshield.subscription.domain.model.PaymentCheckoutRepository;
import pe.buildshield.subscription.shared.error.ResourceNotFoundException;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
class JpaPaymentCheckoutRepository implements PaymentCheckoutRepository {

    private final SpringDataPaymentCheckoutRepository jpa;

    JpaPaymentCheckoutRepository(SpringDataPaymentCheckoutRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<PaymentCheckout> findBySessionId(String sessionId) {
        return jpa.findBySessionId(sessionId).map(PaymentCheckoutJpaEntity::toDomain);
    }

    @Override
    public List<PaymentCheckout> findOpen() {
        return jpa.findByStatusOrderByOpenedAtDesc(CheckoutStatus.OPEN).stream()
                .map(PaymentCheckoutJpaEntity::toDomain).toList();
    }

    @Override
    public PaymentCheckout save(PaymentCheckout checkout) {
        if (checkout.id() == null) {
            return jpa.saveAndFlush(new PaymentCheckoutJpaEntity(checkout)).toDomain();
        }
        PaymentCheckoutJpaEntity entity = jpa.findById(checkout.id()).orElseThrow(() ->
                new ResourceNotFoundException("La sesión de pago " + checkout.id() + " no existe"));
        if (!Objects.equals(entity.getVersion(), checkout.version())) {
            throw new ObjectOptimisticLockingFailureException(PaymentCheckoutJpaEntity.class, checkout.id());
        }
        entity.apply(checkout);
        return jpa.saveAndFlush(entity).toDomain();
    }
}
