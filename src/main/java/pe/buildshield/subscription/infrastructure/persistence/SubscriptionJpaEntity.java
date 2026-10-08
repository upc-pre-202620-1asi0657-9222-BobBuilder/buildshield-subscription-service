package pe.buildshield.subscription.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import pe.buildshield.subscription.domain.model.BillingPeriod;
import pe.buildshield.subscription.domain.model.PlanCode;
import pe.buildshield.subscription.domain.model.Subscription;
import pe.buildshield.subscription.domain.model.SubscriptionStatus;
import pe.buildshield.subscription.shared.persistence.AuditableAbstractAggregateRoot;

import java.time.Instant;

@Entity
@Table(name = "subscriptions")
public class SubscriptionJpaEntity extends AuditableAbstractAggregateRoot<SubscriptionJpaEntity> {

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_code", nullable = false, length = 10)
    private PlanCode planCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_period", length = 10)
    private BillingPeriod billingPeriod;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private SubscriptionStatus status;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;

    @Column(name = "expired_at")
    private Instant expiredAt;

    protected SubscriptionJpaEntity() {
    }

    SubscriptionJpaEntity(Subscription subscription) {
        startedAt = subscription.startedAt();
        apply(subscription);
    }

    void apply(Subscription subscription) {
        planCode = subscription.plan();
        billingPeriod = subscription.billingPeriod();
        status = subscription.status();
        validUntil = subscription.validUntil();
        expiredAt = subscription.expiredAt();
    }

    Subscription toDomain() {
        return Subscription.restore(getId(), getOrganizationId(), planCode, billingPeriod, status, startedAt,
                validUntil, expiredAt, getVersion());
    }
}
