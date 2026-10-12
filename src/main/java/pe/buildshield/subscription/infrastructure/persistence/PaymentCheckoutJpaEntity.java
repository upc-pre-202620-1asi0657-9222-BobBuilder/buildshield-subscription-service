package pe.buildshield.subscription.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import pe.buildshield.subscription.domain.model.BillingPeriod;
import pe.buildshield.subscription.domain.model.CheckoutStatus;
import pe.buildshield.subscription.domain.model.PaymentCheckout;
import pe.buildshield.subscription.domain.model.PaymentProvider;
import pe.buildshield.subscription.domain.model.PlanCode;
import pe.buildshield.subscription.shared.persistence.AuditableAbstractAggregateRoot;

import java.time.Instant;
import java.util.UUID;

/** Sesión de pago de la organización ({@code payment_checkouts}). */
@Entity
@Table(name = "payment_checkouts")
public class PaymentCheckoutJpaEntity extends AuditableAbstractAggregateRoot<PaymentCheckoutJpaEntity> {

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private UUID idempotencyKey;

    @Column(name = "session_id", nullable = false, updatable = false, length = 255)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_code", nullable = false, updatable = false, length = 10)
    private PlanCode planCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_period", nullable = false, updatable = false, length = 10)
    private BillingPeriod billingPeriod;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false, length = 10)
    private PaymentProvider provider;

    @Column(name = "checkout_url", nullable = false, updatable = false, length = 2000)
    private String checkoutUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private CheckoutStatus status;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected PaymentCheckoutJpaEntity() {
    }

    PaymentCheckoutJpaEntity(PaymentCheckout checkout) {
        idempotencyKey = checkout.idempotencyKey();
        sessionId = checkout.sessionId();
        planCode = checkout.plan();
        billingPeriod = checkout.billingPeriod();
        provider = checkout.provider();
        checkoutUrl = checkout.checkoutUrl();
        openedAt = checkout.openedAt();
        apply(checkout);
    }

    void apply(PaymentCheckout checkout) {
        status = checkout.status();
        completedAt = checkout.completedAt();
    }

    PaymentCheckout toDomain() {
        return PaymentCheckout.restore(getId(), getOrganizationId(), idempotencyKey, sessionId, planCode, billingPeriod, provider,
                checkoutUrl, status, openedAt, completedAt, getVersion());
    }
}
