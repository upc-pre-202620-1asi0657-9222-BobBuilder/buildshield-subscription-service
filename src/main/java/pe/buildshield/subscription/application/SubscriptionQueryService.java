package pe.buildshield.subscription.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.subscription.domain.model.BillingPeriod;
import pe.buildshield.subscription.domain.model.PaymentProvider;
import pe.buildshield.subscription.domain.model.Plan;
import pe.buildshield.subscription.domain.model.PlanRepository;
import pe.buildshield.subscription.domain.model.Subscription;
import pe.buildshield.subscription.domain.model.SubscriptionRepository;
import pe.buildshield.subscription.domain.model.SubscriptionStatus;
import pe.buildshield.subscription.shared.error.ResourceNotFoundException;
import pe.buildshield.subscription.shared.tenant.TenantContext;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Consultas: catálogo de planes (US08) y estado de la suscripción de la organización del token (US11). */
@Service
public class SubscriptionQueryService {

    private final PlanRepository plans;
    private final SubscriptionRepository subscriptions;
    private final SubscriptionProperties properties;
    private final Clock clock;

    public SubscriptionQueryService(PlanRepository plans, SubscriptionRepository subscriptions,
            SubscriptionProperties properties, Clock clock) {
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Catálogo global de planes (no pertenece a una organización). Es público: se lee en modo sistema porque la
     * petición de la página de inicio no trae organización (la transacción la abre el repositorio, ya en modo sistema).
     */
    public List<Plan> plans() {
        return TenantContext.callAsSystem(plans::findAll);
    }

    public int trialDays() {
        return properties.trialDays();
    }

    @Transactional(readOnly = true)
    public SubscriptionView current() {
        Subscription subscription = subscriptions.findCurrent().orElseThrow(() -> new ResourceNotFoundException(
                SubscriptionCommandService.SUBSCRIPTION_NOT_FOUND, "La organización todavía no tiene suscripción"));
        Plan plan = plans.findByCode(subscription.plan()).orElseThrow(() -> new IllegalStateException(
                "Plan " + subscription.plan() + " no existe en el catálogo"));
        Instant now = clock.instant();
        boolean readOnly = subscription.readOnly(now);
        SubscriptionStatus status = readOnly ? SubscriptionStatus.EXPIRED : subscription.status();
        return new SubscriptionView(subscription.id(), subscription.organizationId(), plan, status,
                subscription.billingPeriod(), subscription.startedAt(), subscription.validUntil(),
                subscription.daysRemaining(now), readOnly, subscription.currentPeriodStart(),
                readOnly ? null : subscription.nextBillingAt(), subscription.pendingBillingPeriod(),
                subscription.pendingChangeAt(), subscription.paymentProvider());
    }

    /**
     * Estado visible de la suscripción. Si la fecha de acceso ya pasó se muestra vencida aunque el proceso periódico
     * todavía no la haya marcado.
     *
     * @param currentPeriodStart   inicio del periodo actual (de la prueba o del último cobro)
     * @param nextBillingAt        próxima fecha de cobro; nula en la prueba gratuita o si ya venció
     * @param pendingBillingPeriod modalidad programada para el siguiente periodo, o nula
     * @param pendingChangeAt      desde cuándo rige esa modalidad (la próxima fecha de cobro), o nula
     */
    public record SubscriptionView(UUID id, UUID organizationId, Plan plan, SubscriptionStatus status,
            BillingPeriod billingPeriod, Instant startedAt, Instant validUntil, long daysRemaining, boolean readOnly,
            Instant currentPeriodStart, Instant nextBillingAt, BillingPeriod pendingBillingPeriod,
            Instant pendingChangeAt, PaymentProvider paymentProvider) {
    }
}
