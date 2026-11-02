package pe.buildshield.subscription.domain.model;

import pe.buildshield.subscription.shared.error.ConflictException;
import pe.buildshield.subscription.shared.error.ValidationException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/**
 * Suscripción de una organización. Empieza como prueba gratuita del plan Free al registrarse la organización y
 * vence en {@code trialEndsAt}: desde ese momento la cuenta queda en solo lectura hasta pagar.
 *
 * <p>Al confirmarse el pago (US09) pasa a {@link SubscriptionStatus#ACTIVE} con el plan y la modalidad elegidos, y
 * {@code periodEnd} es la fecha (UTC) del próximo cobro. La cuenta sigue operativa durante {@link #PAYMENT_GRACE}
 * después de esa fecha, mientras la pasarela reintenta el cobro. El cambio de modalidad (US12) queda programado y se
 * aplica al empezar el siguiente periodo de cobro.
 */
public class Subscription {

    /** Margen tras la fecha de cobro de una suscripción pagada antes de dejarla en solo lectura. */
    public static final Duration PAYMENT_GRACE = Duration.ofDays(3);

    public static final String SUBSCRIPTION_NOT_ACTIVE = "SUBSCRIPTION_NOT_ACTIVE";
    public static final String INVALID_PAID_PLAN = "INVALID_PAID_PLAN";

    /** Largo de {@code gateway_subscription_id} en el ER. */
    public static final int MAX_GATEWAY_SUBSCRIPTION_ID_LENGTH = 60;

    private final UUID id;
    private final UUID organizationId;
    private PlanCode plan;
    private BillingPeriod billingPeriod;
    private SubscriptionStatus status;
    private final Instant startedAt;
    private final Instant trialEndsAt;
    private LocalDate periodEnd;
    private BillingPeriod pendingBillingPeriod;
    private String gatewaySubscriptionId;
    private final Long version;

    private Subscription(UUID id, UUID organizationId, PlanCode plan, BillingPeriod billingPeriod,
            SubscriptionStatus status, Instant startedAt, Instant trialEndsAt, LocalDate periodEnd,
            BillingPeriod pendingBillingPeriod, String gatewaySubscriptionId, Long version) {
        this.id = id;
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.billingPeriod = billingPeriod;
        this.status = Objects.requireNonNull(status, "status");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        if (trialEndsAt == null && periodEnd == null) {
            throw new IllegalArgumentException("La suscripción necesita el fin de la prueba o el del periodo pagado");
        }
        this.trialEndsAt = trialEndsAt;
        this.periodEnd = periodEnd;
        this.pendingBillingPeriod = pendingBillingPeriod;
        this.gatewaySubscriptionId = normalizeGatewayId(gatewaySubscriptionId);
        this.version = version;
    }

    /** Prueba gratuita del plan Free, con todas las funciones, desde {@code start} durante {@code length}. */
    public static Subscription startTrial(UUID organizationId, Instant start, Duration length) {
        if (length == null || length.isNegative() || length.isZero()) {
            throw new IllegalArgumentException("La prueba gratuita debe durar más que cero");
        }
        return new Subscription(null, organizationId, PlanCode.FREE, null, SubscriptionStatus.TRIAL, start,
                start.plus(length), null, null, null, null);
    }

    public static Subscription restore(UUID id, UUID organizationId, PlanCode plan, BillingPeriod billingPeriod,
            SubscriptionStatus status, Instant startedAt, Instant trialEndsAt, LocalDate periodEnd,
            BillingPeriod pendingBillingPeriod, String gatewaySubscriptionId, Long version) {
        return new Subscription(Objects.requireNonNull(id, "id"), organizationId, plan, billingPeriod, status,
                startedAt, trialEndsAt, periodEnd, pendingBillingPeriod, gatewaySubscriptionId, version);
    }

    /**
     * Pago confirmado por la pasarela: la suscripción queda activa con el plan y la modalidad pagados, el periodo
     * empieza en {@code paidAt} y la próxima fecha de cobro es el fin de ese periodo. Reemplaza la prueba o la
     * suscripción vencida y descarta cualquier cambio de modalidad pendiente.
     *
     * <p>Cada pago de Checkout crea otra suscripción en la pasarela, así que {@code gatewaySubscriptionId} reemplaza al
     * guardado incluso si es nulo: es preferible buscarla por metadatos que usar la de un pago anterior.
     */
    public void activate(PlanCode paidPlan, BillingPeriod period, Instant paidAt, String gatewaySubscriptionId) {
        if (paidPlan == null || paidPlan == PlanCode.FREE || period == null) {
            throw new ValidationException(INVALID_PAID_PLAN, "Solo se activan los planes Plus o Pro con su modalidad");
        }
        plan = paidPlan;
        billingPeriod = period;
        status = SubscriptionStatus.ACTIVE;
        periodEnd = dateOf(period.periodEnd(Objects.requireNonNull(paidAt, "paidAt")));
        pendingBillingPeriod = null;
        this.gatewaySubscriptionId = normalizeGatewayId(gatewaySubscriptionId);
    }

    /**
     * Cobro de la renovación confirmado: empieza el siguiente periodo (desde la fecha de cobro, o desde
     * {@code paidAt} si la suscripción ya había vencido) y, si había un cambio de modalidad programado, se aplica.
     *
     * @return {@code true} si se aplicó un cambio de modalidad programado
     */
    public boolean renew(Instant paidAt) {
        return renew(paidAt, null);
    }

    /**
     * Como {@link #renew(Instant)}, con la suscripción de la pasarela que cobró. Una renovación sigue la misma
     * suscripción: si el evento no trae el id, se conserva el guardado.
     */
    public boolean renew(Instant paidAt, String gatewaySubscriptionId) {
        if (!paid() || billingPeriod == null) {
            throw new ConflictException(SUBSCRIPTION_NOT_ACTIVE, "La prueba gratuita no se renueva: hay que pagar un plan");
        }
        boolean changed = pendingBillingPeriod != null;
        if (changed) {
            billingPeriod = pendingBillingPeriod;
            pendingBillingPeriod = null;
        }
        Instant end = validUntil();
        Instant start = status != SubscriptionStatus.EXPIRED && end.isAfter(paidAt) ? end : paidAt;
        periodEnd = dateOf(billingPeriod.periodEnd(start));
        status = SubscriptionStatus.ACTIVE;
        String charged = normalizeGatewayId(gatewaySubscriptionId);
        if (charged != null) {
            this.gatewaySubscriptionId = charged;
        }
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

    /** Con el modo simulado no hay pasarela que cobre: la renovación se hace sola al llegar la fecha de cobro. */
    public boolean renewsAutomatically(PaymentProvider provider, Instant now) {
        return status == SubscriptionStatus.ACTIVE && provider == PaymentProvider.SIMULATED
                && !now.isBefore(validUntil());
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
        return true;
    }

    /** Cuenta en solo lectura: suscripción vencida o con la fecha de acceso ya pasada. */
    public boolean readOnly(Instant now) {
        return status == SubscriptionStatus.EXPIRED || !now.isBefore(accessUntil());
    }

    /** Hasta cuándo hay acceso: la fecha de fin, más el margen de cobro si está pagada y vigente. */
    public Instant accessUntil() {
        return status == SubscriptionStatus.ACTIVE ? validUntil().plus(PAYMENT_GRACE) : validUntil();
    }

    /** Fin del periodo actual: el del periodo pagado (00:00 UTC de {@code periodEnd}) o el de la prueba. */
    public Instant validUntil() {
        return periodEnd != null ? periodEnd.atStartOfDay(ZoneOffset.UTC).toInstant() : trialEndsAt;
    }

    /** ¿Tiene un plan pagado? En la prueba gratuita no hay modalidad ni pasarela. */
    public boolean paid() {
        return plan != PlanCode.FREE;
    }

    /** Próxima fecha de cobro: solo en una suscripción pagada vigente. */
    public Instant nextBillingAt() {
        return status == SubscriptionStatus.ACTIVE ? validUntil() : null;
    }

    /** Desde cuándo rige el cambio de modalidad programado (el siguiente periodo de cobro), o nulo. */
    public Instant pendingChangeAt() {
        return pendingBillingPeriod == null ? null : validUntil();
    }

    /** Días que faltan para el vencimiento (redondeado hacia arriba); 0 si ya venció. */
    public long daysRemaining(Instant now) {
        if (readOnly(now)) {
            return 0;
        }
        long seconds = Duration.between(now, validUntil()).toSeconds();
        return Math.max(0, (seconds + 86_399) / 86_400);
    }

    /**
     * Inicio del periodo actual: el de la prueba o, en un plan pagado, una modalidad antes de {@code periodEnd}. El
     * ER no lo guarda: se deriva con precisión de fecha.
     */
    public Instant currentPeriodStart() {
        return periodEnd == null || billingPeriod == null ? startedAt : billingPeriod.periodStart(validUntil());
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

    public Instant trialEndsAt() {
        return trialEndsAt;
    }

    public LocalDate periodEnd() {
        return periodEnd;
    }

    public BillingPeriod pendingBillingPeriod() {
        return pendingBillingPeriod;
    }

    /** Id de la suscripción en la pasarela ({@code sub_...}), o nulo si todavía no llegó en un webhook. */
    public String gatewaySubscriptionId() {
        return gatewaySubscriptionId;
    }

    public Long version() {
        return version;
    }

    /** Un id más largo que la columna no se guarda: el cambio de modalidad la ubica por metadatos. */
    private static String normalizeGatewayId(String id) {
        return id == null || id.length() > MAX_GATEWAY_SUBSCRIPTION_ID_LENGTH ? null : id;
    }

    private static LocalDate dateOf(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate();
    }

    /** Resultado de pedir un cambio de modalidad. */
    public enum BillingPeriodChange {
        SCHEDULED, CANCELLED, UNCHANGED
    }
}
