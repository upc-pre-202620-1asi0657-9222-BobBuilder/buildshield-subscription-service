package pe.buildshield.subscription.domain.model;

import pe.buildshield.subscription.shared.error.ConflictException;
import pe.buildshield.subscription.shared.error.ValidationException;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Suscripción de una organización. Empieza como prueba gratuita del plan Free al registrarse la organización y
 * vence en {@code validUntil}: desde ese momento la cuenta queda en solo lectura hasta pagar.
 *
 * <p>Al confirmarse el pago (US09) pasa a {@link SubscriptionStatus#ACTIVE} con el plan y la modalidad elegidos, y
 * {@code validUntil} es la próxima fecha de cobro. Con Stripe, si el cobro de la renovación se demora, la cuenta
 * sigue operativa durante {@link #PAYMENT_GRACE} (Stripe reintenta el cobro) antes de vencer. El cambio de
 * modalidad (US12) queda programado y se aplica al empezar el siguiente periodo de cobro.
 */
public class Subscription {

    /** Margen tras la fecha de cobro de una suscripción pagada con Stripe antes de dejarla en solo lectura. */
    public static final Duration PAYMENT_GRACE = Duration.ofDays(3);

    public static final String SUBSCRIPTION_NOT_ACTIVE = "SUBSCRIPTION_NOT_ACTIVE";
    public static final String INVALID_PAID_PLAN = "INVALID_PAID_PLAN";

    private final UUID id;
    private final UUID organizationId;
    private PlanCode plan;
    private BillingPeriod billingPeriod;
    private SubscriptionStatus status;
    private final Instant startedAt;
    private Instant validUntil;
    private Instant expiredAt;
    private Instant currentPeriodStart;
    private BillingPeriod pendingBillingPeriod;
    private PaymentProvider paymentProvider;
    private String paymentCustomerId;
    private String paymentSubscriptionId;
    private final Long version;

    private Subscription(UUID id, UUID organizationId, PlanCode plan, BillingPeriod billingPeriod,
            SubscriptionStatus status, Instant startedAt, Instant validUntil, Instant expiredAt, Payment payment,
            Long version) {
        this.id = id;
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.billingPeriod = billingPeriod;
        this.status = Objects.requireNonNull(status, "status");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.validUntil = Objects.requireNonNull(validUntil, "validUntil");
        this.expiredAt = expiredAt;
        Payment details = payment == null ? Payment.NONE : payment;
        this.currentPeriodStart = details.currentPeriodStart() == null ? startedAt : details.currentPeriodStart();
        this.pendingBillingPeriod = details.pendingBillingPeriod();
        this.paymentProvider = details.provider();
        this.paymentCustomerId = details.customerId();
        this.paymentSubscriptionId = details.providerSubscriptionId();
        this.version = version;
    }

    /** Prueba gratuita del plan Free, con todas las funciones, desde {@code start} durante {@code length}. */
    public static Subscription startTrial(UUID organizationId, Instant start, Duration length) {
        if (length == null || length.isNegative() || length.isZero()) {
            throw new IllegalArgumentException("La prueba gratuita debe durar más que cero");
        }
        return new Subscription(null, organizationId, PlanCode.FREE, null, SubscriptionStatus.TRIAL, start,
                start.plus(length), null, null, null);
    }

    public static Subscription restore(UUID id, UUID organizationId, PlanCode plan, BillingPeriod billingPeriod,
            SubscriptionStatus status, Instant startedAt, Instant validUntil, Instant expiredAt, Long version) {
        return restore(id, organizationId, plan, billingPeriod, status, startedAt, validUntil, expiredAt, null,
                version);
    }

    public static Subscription restore(UUID id, UUID organizationId, PlanCode plan, BillingPeriod billingPeriod,
            SubscriptionStatus status, Instant startedAt, Instant validUntil, Instant expiredAt, Payment payment,
            Long version) {
        return new Subscription(Objects.requireNonNull(id, "id"), organizationId, plan, billingPeriod, status,
                startedAt, validUntil, expiredAt, payment, version);
    }

    /**
     * Pago confirmado por la pasarela: la suscripción queda activa con el plan y la modalidad pagados, el periodo
     * empieza en {@code paidAt} y la próxima fecha de cobro es el fin de ese periodo. Reemplaza la prueba o la
     * suscripción vencida y descarta cualquier cambio de modalidad pendiente.
     */
    public void activate(PlanCode paidPlan, BillingPeriod period, Instant paidAt, PaymentProvider provider,
            String customerId, String providerSubscriptionId) {
        if (paidPlan == null || paidPlan == PlanCode.FREE || period == null || provider == null) {
            throw new ValidationException(INVALID_PAID_PLAN, "Solo se activan los planes Plus o Pro con su modalidad");
        }
        plan = paidPlan;
        billingPeriod = period;
        status = SubscriptionStatus.ACTIVE;
        currentPeriodStart = Objects.requireNonNull(paidAt, "paidAt");
        validUntil = period.periodEnd(paidAt);
        expiredAt = null;
        pendingBillingPeriod = null;
        paymentProvider = provider;
        paymentCustomerId = customerId;
        paymentSubscriptionId = providerSubscriptionId;
    }

    /**
     * Cobro de la renovación confirmado: empieza el siguiente periodo (desde la fecha de cobro, o desde
     * {@code paidAt} si la suscripción ya había vencido) y, si había un cambio de modalidad programado, se aplica.
     *
     * @return {@code true} si se aplicó un cambio de modalidad programado
     */
    public boolean renew(Instant paidAt) {
        if (plan == PlanCode.FREE || billingPeriod == null) {
            throw new ConflictException(SUBSCRIPTION_NOT_ACTIVE, "La prueba gratuita no se renueva: hay que pagar un plan");
        }
        boolean changed = pendingBillingPeriod != null;
        if (changed) {
            billingPeriod = pendingBillingPeriod;
            pendingBillingPeriod = null;
        }
        Instant start = status != SubscriptionStatus.EXPIRED && validUntil.isAfter(paidAt) ? validUntil : paidAt;
        currentPeriodStart = start;
        validUntil = billingPeriod.periodEnd(start);
        status = SubscriptionStatus.ACTIVE;
        expiredAt = null;
        return changed;
    }

    /**
     * Programa el cambio de modalidad para el siguiente periodo de cobro (US12). Pedir la modalidad actual cancela
     * un cambio pendiente; pedir de nuevo la que ya está programada no cambia nada.
     */
    public BillingPeriodChange scheduleBillingPeriodChange(BillingPeriod target, Instant now) {
        Objects.requireNonNull(target, "target");
        if (status != SubscriptionStatus.ACTIVE || readOnly(now)) {
            throw new ConflictException(SUBSCRIPTION_NOT_ACTIVE,
                    "Solo una suscripción pagada y vigente puede cambiar de modalidad");
        }
        if (target == billingPeriod) {
            if (pendingBillingPeriod == null) {
                return BillingPeriodChange.UNCHANGED;
            }
            pendingBillingPeriod = null;
            return BillingPeriodChange.CANCELLED;
        }
        if (target == pendingBillingPeriod) {
            return BillingPeriodChange.UNCHANGED;
        }
        pendingBillingPeriod = target;
        return BillingPeriodChange.SCHEDULED;
    }

    /** ¿Ya pasó la fecha hasta la que hay acceso y todavía figura como vigente? */
    public boolean isDue(Instant now) {
        return status != SubscriptionStatus.EXPIRED && !now.isBefore(accessUntil());
    }

    /** En el modo simulado no hay pasarela que cobre: la renovación se hace sola al llegar la fecha de cobro. */
    public boolean renewsAutomatically(Instant now) {
        return status == SubscriptionStatus.ACTIVE && paymentProvider == PaymentProvider.SIMULATED
                && !now.isBefore(validUntil);
    }

    /**
     * Vence la suscripción si ya pasó su fecha de acceso.
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

    /** Cuenta en solo lectura: suscripción vencida o con la fecha de acceso ya pasada. */
    public boolean readOnly(Instant now) {
        return status == SubscriptionStatus.EXPIRED || !now.isBefore(accessUntil());
    }

    /** Hasta cuándo hay acceso: la fecha de fin, más el margen de cobro si la paga Stripe. */
    public Instant accessUntil() {
        return status == SubscriptionStatus.ACTIVE && paymentProvider == PaymentProvider.STRIPE
                ? validUntil.plus(PAYMENT_GRACE) : validUntil;
    }

    /** Próxima fecha de cobro: solo en una suscripción pagada vigente. */
    public Instant nextBillingAt() {
        return status == SubscriptionStatus.ACTIVE ? validUntil : null;
    }

    /** Desde cuándo rige el cambio de modalidad programado (el siguiente periodo de cobro), o nulo. */
    public Instant pendingChangeAt() {
        return pendingBillingPeriod == null ? null : validUntil;
    }

    /** Días que faltan para el vencimiento (redondeado hacia arriba); 0 si ya venció. */
    public long daysRemaining(Instant now) {
        if (readOnly(now)) {
            return 0;
        }
        long seconds = Duration.between(now, validUntil).toSeconds();
        return Math.max(0, (seconds + 86_399) / 86_400);
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

    /** Inicio del periodo actual: el de la prueba o el del último cobro. */
    public Instant currentPeriodStart() {
        return currentPeriodStart;
    }

    public BillingPeriod pendingBillingPeriod() {
        return pendingBillingPeriod;
    }

    public PaymentProvider paymentProvider() {
        return paymentProvider;
    }

    public String paymentCustomerId() {
        return paymentCustomerId;
    }

    public String paymentSubscriptionId() {
        return paymentSubscriptionId;
    }

    /** Datos de cobro guardados (nunca datos de tarjeta: solo identificadores de la pasarela). */
    public Payment payment() {
        return new Payment(currentPeriodStart, pendingBillingPeriod, paymentProvider, paymentCustomerId,
                paymentSubscriptionId);
    }

    public Long version() {
        return version;
    }

    /** Resultado de pedir un cambio de modalidad. */
    public enum BillingPeriodChange {
        SCHEDULED, CANCELLED, UNCHANGED
    }

    /**
     * Datos del cobro: periodo actual, cambio programado y los identificadores de la pasarela (cliente y
     * suscripción en Stripe). Nulos en la prueba gratuita.
     */
    public record Payment(Instant currentPeriodStart, BillingPeriod pendingBillingPeriod, PaymentProvider provider,
            String customerId, String providerSubscriptionId) {

        public static final Payment NONE = new Payment(null, null, null, null, null);
    }
}
