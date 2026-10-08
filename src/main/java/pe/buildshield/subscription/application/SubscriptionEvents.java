package pe.buildshield.subscription.application;

import pe.buildshield.subscription.domain.model.Subscription;

import java.time.Instant;
import java.util.UUID;

/**
 * Eventos de integración que publica subscription en {@code buildshield.subscription} (payload del sobre). iam los
 * consume para {@code organization_access} y lee {@code organizationId}, {@code plan}, {@code status} y
 * {@code validUntil}: esos nombres no se cambian. Los demás campos son adicionales.
 */
public final class SubscriptionEvents {

    public static final String SUBSCRIPTION_ACTIVATED = "SubscriptionActivated";
    public static final String SUBSCRIPTION_EXPIRED = "SubscriptionExpired";
    public static final String SUBSCRIPTION = "SUBSCRIPTION";

    private SubscriptionEvents() {
    }

    /**
     * Estado de la suscripción de la organización.
     *
     * @param plan          FREE, PLUS o PRO
     * @param status        TRIAL, ACTIVE o EXPIRED
     * @param billingPeriod MONTHLY, ANNUAL o nulo en la prueba gratuita
     */
    public record SubscriptionChanged(UUID organizationId, UUID subscriptionId, String plan, String status,
            Instant validUntil, String billingPeriod, Instant startedAt) {

        static SubscriptionChanged of(Subscription subscription) {
            return new SubscriptionChanged(subscription.organizationId(), subscription.id(),
                    subscription.plan().name(), subscription.status().name(), subscription.validUntil(),
                    subscription.billingPeriod() == null ? null : subscription.billingPeriod().name(),
                    subscription.startedAt());
        }
    }
}
