package pe.buildshield.subscription.domain.model;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Cobro de la suscripción (US07, US09). Nunca guarda datos de tarjeta: solo el monto, el estado y el identificador
 * del cobro en la pasarela. Un cobro de Checkout nace {@link PaymentStatus#PENDING} con la clave de idempotencia de
 * la sesión y se completa una sola vez, cuando llega la confirmación; una renovación nace ya pagada.
 */
public class Payment {

    /** Mientras esté pendiente y sea reciente, un nuevo pedido del mismo monto reutiliza la sesión de pago. */
    public static final Duration REUSE_WINDOW = Duration.ofMinutes(30);

    /** Largo de {@code gateway_charge_id} en el ER. */
    public static final int MAX_CHARGE_ID_LENGTH = 60;

    private final UUID id;
    private final UUID subscriptionId;
    private final UUID organizationId;
    private final UUID idempotencyKey;
    private String gatewayChargeId;
    private final BigDecimal amount;
    private PaymentStatus status;
    private final String failureReason;
    private final Instant createdAt;

    private Payment(UUID id, UUID subscriptionId, UUID organizationId, UUID idempotencyKey, String gatewayChargeId,
            BigDecimal amount, PaymentStatus status, String failureReason, Instant createdAt) {
        this.id = id;
        this.subscriptionId = Objects.requireNonNull(subscriptionId, "subscriptionId");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.gatewayChargeId = normalize(gatewayChargeId);
        this.amount = Objects.requireNonNull(amount, "amount");
        this.status = Objects.requireNonNull(status, "status");
        this.failureReason = failureReason;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Cobro de una sesión de pago recién abierta con esa clave de idempotencia. El id lo asigna la persistencia. */
    public static Payment open(UUID subscriptionId, UUID organizationId, UUID idempotencyKey, BigDecimal amount,
            Instant createdAt) {
        return new Payment(null, subscriptionId, organizationId, idempotencyKey, null, amount, PaymentStatus.PENDING,
                null, createdAt);
    }

    /** Cobro ya confirmado por la pasarela (renovación). */
    public static Payment paid(UUID subscriptionId, UUID organizationId, UUID idempotencyKey, String gatewayChargeId,
            BigDecimal amount, Instant createdAt) {
        return new Payment(null, subscriptionId, organizationId, idempotencyKey, gatewayChargeId, amount,
                PaymentStatus.PAID, null, createdAt);
    }

    public static Payment restore(UUID id, UUID subscriptionId, UUID organizationId, UUID idempotencyKey,
            String gatewayChargeId, BigDecimal amount, PaymentStatus status, String failureReason, Instant createdAt) {
        return new Payment(Objects.requireNonNull(id, "id"), subscriptionId, organizationId, idempotencyKey,
                gatewayChargeId, amount, status, failureReason, createdAt);
    }

    /**
     * Marca el cobro como pagado.
     *
     * @return {@code false} si ya estaba pagado (la confirmación llegó dos veces)
     */
    public boolean complete(String chargeId) {
        if (status != PaymentStatus.PENDING) {
            return false;
        }
        status = PaymentStatus.PAID;
        gatewayChargeId = normalize(chargeId);
        return true;
    }

    /** ¿Sirve para un nuevo pedido de ese monto? Pendiente y creado hace menos de 30 minutos. */
    public boolean reusableFor(BigDecimal requestedAmount, Instant now) {
        return matchesPending(requestedAmount) && now.isBefore(createdAt.plus(REUSE_WINDOW));
    }

    /** ¿Está pendiente y es de ese monto? */
    public boolean matchesPending(BigDecimal requestedAmount) {
        return status == PaymentStatus.PENDING && amount.compareTo(requestedAmount) == 0;
    }

    /** Un identificador más largo que la columna no se guarda: es trazabilidad, el cobro sigue en la pasarela. */
    private static String normalize(String chargeId) {
        return chargeId == null || chargeId.length() > MAX_CHARGE_ID_LENGTH ? null : chargeId;
    }

    public UUID id() {
        return id;
    }

    public UUID subscriptionId() {
        return subscriptionId;
    }

    public UUID organizationId() {
        return organizationId;
    }

    /** Clave de idempotencia enviada a la pasarela al crear la sesión. */
    public UUID idempotencyKey() {
        return idempotencyKey;
    }

    public String gatewayChargeId() {
        return gatewayChargeId;
    }

    public BigDecimal amount() {
        return amount;
    }

    public PaymentStatus status() {
        return status;
    }

    public String failureReason() {
        return failureReason;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
