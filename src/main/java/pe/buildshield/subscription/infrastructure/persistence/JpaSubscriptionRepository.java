package pe.buildshield.subscription.infrastructure.persistence;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import pe.buildshield.subscription.domain.model.Subscription;
import pe.buildshield.subscription.domain.model.SubscriptionRepository;
import pe.buildshield.subscription.shared.error.ResourceNotFoundException;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaSubscriptionRepository implements SubscriptionRepository {

    private final SpringDataSubscriptionRepository jpa;

    JpaSubscriptionRepository(SpringDataSubscriptionRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Subscription> findCurrent() {
        return jpa.findFirstByOrderByCreatedAtDesc().map(SubscriptionJpaEntity::toDomain);
    }

    @Override
    public List<UUID> findOrganizationsDue(Instant now) {
        return jpa.findOrganizationsDue(now);
    }

    @Override
    public Optional<UUID> findOrganizationByPaymentSubscriptionId(String paymentSubscriptionId) {
        return jpa.findOrganizationByPaymentSubscriptionId(paymentSubscriptionId);
    }

    @Override
    public Subscription save(Subscription subscription) {
        if (subscription.id() == null) {
            return jpa.saveAndFlush(new SubscriptionJpaEntity(subscription)).toDomain();
        }
        SubscriptionJpaEntity entity = jpa.findById(subscription.id()).orElseThrow(() ->
                new ResourceNotFoundException("La suscripción " + subscription.id() + " no existe"));
        if (!Objects.equals(entity.getVersion(), subscription.version())) {
            throw new ObjectOptimisticLockingFailureException(SubscriptionJpaEntity.class, subscription.id());
        }
        entity.apply(subscription);
        return jpa.saveAndFlush(entity).toDomain();
    }
}
