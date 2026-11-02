package pe.buildshield.subscription.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import pe.buildshield.subscription.domain.model.BillingPeriod;
import pe.buildshield.subscription.domain.model.PlanCode;
import pe.buildshield.subscription.domain.model.Subscription;
import pe.buildshield.subscription.domain.model.SubscriptionStatus;
import pe.buildshield.subscription.shared.persistence.AuditableAbstractAggregateRoot;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "subscriptions")
public class SubscriptionJpaEntity extends AuditableAbstractAggregateRoot<SubscriptionJpaEntity> {

    /** Modalidad con la que se guarda la prueba gratuita: la columna es NOT NULL y la prueba no tiene modalidad. */
    private static final BillingPeriod TRIAL_BILLING_PERIOD = BillingPeriod.MONTHLY;

    @ManyToOne(optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private PlanJpaEntity plan;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_period", nullable = false, length = 10)
    private BillingPeriod billingPeriod;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    private SubscriptionStatus status;

    @Column(name = "trial_ends_at")
    private Instant trialEndsAt;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    /** Modalidad que rige desde el siguiente periodo de cobro. */
    @Enumerated(EnumType.STRING)
    @Column(name = "pending_billing_period", length = 10)
    private BillingPeriod pendingBillingPeriod;

    /** Id de la suscripción en Stripe ({@code sub_...}), o nulo si todavía no llegó en un webhook. */
    @Column(name = "gateway_subscription_id", length = 60)
    private String gatewaySubscriptionId;

    @Transient
    private Instant startedAt;

    protected SubscriptionJpaEntity() {
    }

    SubscriptionJpaEntity(Subscription subscription, PlanJpaEntity plan) {
        startedAt = subscription.startedAt();
        apply(subscription, plan);
    }

    /** {@code created_at} es el alta de la suscripción: la prueba cuenta desde el registro de la organización. */
    @PrePersist
    void keepStartDate() {
        if (startedAt != null) {
            overrideCreatedAt(startedAt);
        }
    }

    void apply(Subscription subscription, PlanJpaEntity currentPlan) {
        plan = currentPlan;
        billingPeriod = subscription.billingPeriod() == null ? TRIAL_BILLING_PERIOD : subscription.billingPeriod();
        status = subscription.status();
        trialEndsAt = subscription.trialEndsAt();
        periodEnd = subscription.periodEnd();
        pendingBillingPeriod = subscription.pendingBillingPeriod();
        gatewaySubscriptionId = subscription.gatewaySubscriptionId();
    }

    Subscription toDomain() {
        PlanCode code = plan.toDomain().code();
        return Subscription.restore(getId(), getOrganizationId(), code, code == PlanCode.FREE ? null : billingPeriod,
                status, getCreatedAt(), trialEndsAt, periodEnd, pendingBillingPeriod, gatewaySubscriptionId,
                getVersion());
    }
}
