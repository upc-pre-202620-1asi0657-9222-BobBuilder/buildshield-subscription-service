package pe.buildshield.subscription.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import pe.buildshield.subscription.domain.model.Payment;
import pe.buildshield.subscription.domain.model.PaymentStatus;
import pe.buildshield.subscription.shared.persistence.OrganizationScopedEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Cobro de la suscripción ({@code payments}). El ER no le da auditoría ni versión: solo {@code created_at}. */
@Entity
@Table(name = "payments")
public class PaymentJpaEntity extends OrganizationScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "subscription_id", nullable = false, updatable = false)
    private UUID subscriptionId;

    @Column(name = "idempotency_key", nullable = false, updatable = false, unique = true)
    private UUID idempotencyKey;

    @Column(name = "gateway_charge_id", length = 60, unique = true)
    private String gatewayChargeId;

    @Column(name = "amount", nullable = false, updatable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    private PaymentStatus status;

    @Column(name = "failure_reason", length = 120)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PaymentJpaEntity() {
    }

    PaymentJpaEntity(Payment payment) {
        subscriptionId = payment.subscriptionId();
        idempotencyKey = payment.idempotencyKey();
        amount = payment.amount();
        createdAt = payment.createdAt();
        apply(payment);
    }

    void apply(Payment payment) {
        gatewayChargeId = payment.gatewayChargeId();
        status = payment.status();
        failureReason = payment.failureReason();
    }

    Payment toDomain() {
        return Payment.restore(id, subscriptionId, getOrganizationId(), idempotencyKey, gatewayChargeId, amount, status,
                failureReason, createdAt);
    }
}
