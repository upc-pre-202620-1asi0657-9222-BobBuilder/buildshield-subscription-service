package pe.buildshield.subscription.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.subscription.domain.model.BillingPeriod;
import pe.buildshield.subscription.domain.model.Plan;
import pe.buildshield.subscription.domain.model.PlanRepository;
import pe.buildshield.subscription.domain.model.Subscription;
import pe.buildshield.subscription.domain.model.SubscriptionRepository;
import pe.buildshield.subscription.domain.model.SubscriptionStatus;
import pe.buildshield.subscription.shared.error.ResourceNotFoundException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Consultas: catálogo de planes y estado de la suscripción de la organización del token (US09). */
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

    @Transactional(readOnly = true)
    public List<Plan> plans() {
        return plans.findAll();
    }

    public int trialDays() {
        return properties.trialDays();
    }

    @Transactional(readOnly = true)
    public SubscriptionView current() {
        Subscription subscription = subscriptions.findCurrent().orElseThrow(() -> new ResourceNotFoundException(
                "SUBSCRIPTION_NOT_FOUND", "La organización todavía no tiene suscripción"));
        Plan plan = plans.findByCode(subscription.plan()).orElseThrow(() -> new IllegalStateException(
                "Plan " + subscription.plan() + " no existe en el catálogo"));
        Instant now = clock.instant();
        SubscriptionStatus status = subscription.readOnly(now) ? SubscriptionStatus.EXPIRED : subscription.status();
        return new SubscriptionView(subscription.id(), subscription.organizationId(), plan, status,
                subscription.billingPeriod(), subscription.startedAt(), subscription.validUntil(),
                subscription.daysRemaining(now), subscription.readOnly(now));
    }

    /**
     * Estado visible de la suscripción. Si la fecha de fin ya pasó se muestra vencida aunque el proceso periódico
     * todavía no la haya marcado.
     */
    public record SubscriptionView(UUID id, UUID organizationId, Plan plan, SubscriptionStatus status,
            BillingPeriod billingPeriod, Instant startedAt, Instant validUntil, long daysRemaining, boolean readOnly) {
    }
}
