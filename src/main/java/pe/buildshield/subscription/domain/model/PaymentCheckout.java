package pe.buildshield.subscription.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Sesión de pago abierta en la pasarela para contratar un plan en una modalidad (US07, US09). Solo guarda el
 * identificador de la sesión y la URL a la que se redirige al cliente: los datos de tarjeta los recibe la pasarela.
 * Se completa una sola vez, cuando llega la confirmación del pago.
 */
public class PaymentCheckout {

    /** Mientras esté abierta y sea reciente, un nuevo pedido del mismo plan y modalidad reutiliza la sesión. */
    public static final Duration REUSE_WINDOW = Duration.ofMinutes(30);

    private final UUID id;
    private final UUID organizationId;
    private final UUID idempotencyKey;
    private final String sessionId;
    private final PlanCode plan;
    private final BillingPeriod billingPeriod;
    private final PaymentProvider provider;
    private final String checkoutUrl;
    private CheckoutStatus status;
    private final Instant openedAt;
    private Instant completedAt;
    private final Long version;

    private PaymentCheckout(UUID id, UUID organizationId, UUID idempotencyKey, String sessionId, PlanCode plan, BillingPeriod billingPeriod,
            PaymentProvider provider, String checkoutUrl, CheckoutStatus status, Instant openedAt, Instant completedAt,
            Long version) {
        this.id = id;
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.billingPeriod = Objects.requireNonNull(billingPeriod, "billingPeriod");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.checkoutUrl = Objects.requireNonNull(checkoutUrl, "checkoutUrl");
        this.status = Objects.requireNonNull(status, "status");
        this.openedAt = Objects.requireNonNull(openedAt, "openedAt");
        this.completedAt = completedAt;
        this.version = version;
    }

    /** Sesión recién creada en la pasarela con esa clave de idempotencia. El id lo asigna la persistencia. */
    public static PaymentCheckout open(UUID idempotencyKey, UUID organizationId, String sessionId, PlanCode plan,
            BillingPeriod billingPeriod, PaymentProvider provider, String checkoutUrl, Instant openedAt) {
        if (plan == PlanCode.FREE) {
            throw new IllegalArgumentException("El plan Free no se paga");
        }
        return new PaymentCheckout(null, organizationId, idempotencyKey, sessionId, plan, billingPeriod, provider,
                checkoutUrl, CheckoutStatus.OPEN, openedAt, null, null);
    }

    public static PaymentCheckout restore(UUID id, UUID organizationId, UUID idempotencyKey, String sessionId,
            PlanCode plan, BillingPeriod billingPeriod, PaymentProvider provider, String checkoutUrl,
            CheckoutStatus status, Instant openedAt, Instant completedAt, Long version) {
        return new PaymentCheckout(Objects.requireNonNull(id, "id"), organizationId, idempotencyKey, sessionId, plan,
                billingPeriod, provider, checkoutUrl, status, openedAt, completedAt, version);
    }

    /**
     * Marca la sesión como pagada.
     *
     * @return {@code false} si ya estaba completada (la confirmación llegó dos veces)
     */
    public boolean complete(Instant now) {
        if (status == CheckoutStatus.COMPLETED) {
            return false;
        }
        status = CheckoutStatus.COMPLETED;
        completedAt = now;
        return true;
    }

    /** ¿Sirve para un nuevo pedido del mismo plan y modalidad? Abierta y creada hace menos de 30 minutos. */
    public boolean reusableFor(PlanCode requestedPlan, BillingPeriod requestedPeriod, PaymentProvider currentProvider,
            Instant now) {
        return status == CheckoutStatus.OPEN && plan == requestedPlan && billingPeriod == requestedPeriod
                && provider == currentProvider && now.isBefore(openedAt.plus(REUSE_WINDOW));
    }

    public UUID id() {
        return id;
    }

    public UUID organizationId() {
        return organizationId;
    }

    /** Clave de idempotencia enviada a la pasarela al crear la sesión. */
    public UUID idempotencyKey() {
        return idempotencyKey;
    }

    public String sessionId() {
        return sessionId;
    }

    public PlanCode plan() {
        return plan;
    }

    public BillingPeriod billingPeriod() {
        return billingPeriod;
    }

    public PaymentProvider provider() {
        return provider;
    }

    public String checkoutUrl() {
        return checkoutUrl;
    }

    public CheckoutStatus status() {
        return status;
    }

    public Instant openedAt() {
        return openedAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public Long version() {
        return version;
    }
}
