package pe.buildshield.subscription.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Suscripción de una organización. Empieza como prueba gratuita del plan Free al registrarse la organización y
 * vence en {@code validUntil}: desde ese momento la cuenta queda en solo lectura hasta pagar (Sprint 4).
 */
public class Subscription {

    private final UUID id;
    private final UUID organizationId;
    private final PlanCode plan;
    private final BillingPeriod billingPeriod;
    private SubscriptionStatus status;
    private final Instant startedAt;
    private final Instant validUntil;
    private Instant expiredAt;
    private final Long version;

    private Subscription(UUID id, UUID organizationId, PlanCode plan, BillingPeriod billingPeriod,
            SubscriptionStatus status, Instant startedAt, Instant validUntil, Instant expiredAt, Long version) {
        this.id = id;
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.billingPeriod = billingPeriod;
        this.status = Objects.requireNonNull(status, "status");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.validUntil = Objects.requireNonNull(validUntil, "validUntil");
        this.expiredAt = expiredAt;
        this.version = version;
    }

    /** Prueba gratuita del plan Free, con todas las funciones, desde {@code start} durante {@code length}. */
    public static Subscription startTrial(UUID organizationId, Instant start, Duration length) {
        if (length == null || length.isNegative() || length.isZero()) {
            throw new IllegalArgumentException("La prueba gratuita debe durar más que cero");
        }
        return new Subscription(null, organizationId, PlanCode.FREE, null, SubscriptionStatus.TRIAL, start,
                start.plus(length), null, null);
    }

    public static Subscription restore(UUID id, UUID organizationId, PlanCode plan, BillingPeriod billingPeriod,
            SubscriptionStatus status, Instant startedAt, Instant validUntil, Instant expiredAt, Long version) {
        return new Subscription(Objects.requireNonNull(id, "id"), organizationId, plan, billingPeriod, status,
                startedAt, validUntil, expiredAt, version);
    }

    /** ¿Ya pasó la fecha de fin y todavía figura como vigente? */
    public boolean isDue(Instant now) {
        return status != SubscriptionStatus.EXPIRED && !now.isBefore(validUntil);
    }

    /**
     * Vence la suscripción si ya pasó su fecha de fin.
     *
     * @return {@code true} si cambió a vencida (hay que avisar a los demás contextos)
     */
    public boolean expireIfDue(Instant now) {
        if (!isDue(now)) {
            return false;
        }
        status = SubscriptionStatus.EXPIRED;
        expiredAt = now;
        return true;
    }

    /** Cuenta en solo lectura: suscripción vencida o con la fecha de fin ya pasada. */
    public boolean readOnly(Instant now) {
        return status == SubscriptionStatus.EXPIRED || !now.isBefore(validUntil);
    }

    /** Días que faltan para el vencimiento (redondeado hacia arriba); 0 si ya venció. */
    public long daysRemaining(Instant now) {
        if (readOnly(now)) {
            return 0;
        }
        long seconds = Duration.between(now, validUntil).toSeconds();
        return (seconds + 86_399) / 86_400;
    }

    public UUID id() {
        return id;
    }

    public UUID organizationId() {
        return organizationId;
    }

    public PlanCode plan() {
        return plan;
    }

    public BillingPeriod billingPeriod() {
        return billingPeriod;
    }

    public SubscriptionStatus status() {
        return status;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant validUntil() {
        return validUntil;
    }

    public Instant expiredAt() {
        return expiredAt;
    }

    public Long version() {
        return version;
    }
}
