package pe.buildshield.subscription.infrastructure.persistence;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import pe.buildshield.subscription.domain.model.Subscription;
import pe.buildshield.subscription.domain.model.SubscriptionRepository;
import pe.buildshield.subscription.shared.error.ResourceNotFoundException;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaSubscriptionRepository implements SubscriptionRepository {

    private final SpringDataSubscriptionRepository jpa;
    private final SpringDataPlanRepository plans;

    JpaSubscriptionRepository(SpringDataSubscriptionRepository jpa, SpringDataPlanRepository plans) {
        this.jpa = jpa;
        this.plans = plans;
    }

    @Override
    public Optional<Subscription> findCurrent() {
        return jpa.findFirstByOrderByCreatedAtDesc().map(SubscriptionJpaEntity::toDomain);
    }

    @Override
    public List<UUID> findOrganizationsDue(Instant now) {
        return jpa.findOrganizationsDue(now, now.atZone(ZoneOffset.UTC).toLocalDate());
    }

    @Override
    public Subscription save(Subscription subscription) {
        if (subscription.id() == null) {
            return jpa.saveAndFlush(new SubscriptionJpaEntity(subscription, plan(subscription))).toDomain();
        }
        SubscriptionJpaEntity entity = jpa.findById(subscription.id()).orElseThrow(() ->
                new ResourceNotFoundException("La suscripción " + subscription.id() + " no existe"));
        if (!Objects.equals(entity.getVersion(), subscription.version())) {
            throw new ObjectOptimisticLockingFailureException(SubscriptionJpaEntity.class, subscription.id());
        }
        entity.apply(subscription, plan(subscription));
        return jpa.saveAndFlush(entity).toDomain();
    }

    private PlanJpaEntity plan(Subscription subscription) {
        return plans.findByCode(subscription.plan().name()).orElseThrow(() ->
                new IllegalStateException("Plan " + subscription.plan() + " no existe en el catálogo"));
    }
}
