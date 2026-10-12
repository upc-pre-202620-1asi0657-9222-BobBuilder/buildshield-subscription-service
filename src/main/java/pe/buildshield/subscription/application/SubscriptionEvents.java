package pe.buildshield.subscription.application;

import pe.buildshield.subscription.domain.model.Subscription;

import java.time.Instant;
import java.util.UUID;

/**
 * Eventos de integración que publica subscription en {@code buildshield.subscription} (payload del sobre). iam los
 * consume para {@code organization_access} y lee {@code organizationId}, {@code plan}, {@code status} y
 * {@code validUntil}: esos nombres no se cambian. organization lee además {@code maxActiveWorksites} para aplicar el
 * límite de obras activas del plan. Los demás campos son adicionales.
 */
public final class SubscriptionEvents {

    public static final String SUBSCRIPTION_ACTIVATED = "SubscriptionActivated";
    public static final String SUBSCRIPTION_EXPIRED = "SubscriptionExpired";
    public static final String BILLING_PERIOD_CHANGE_SCHEDULED = "SubscriptionBillingPeriodChangeScheduled";
    public static final String SUBSCRIPTION = "SUBSCRIPTION";

    /** Motivos de {@code SubscriptionActivated}. */
    public static final String REASON_TRIAL_STARTED = "TRIAL_STARTED";
    public static final String REASON_PAYMENT_CONFIRMED = "PAYMENT_CONFIRMED";
    public static final String REASON_RENEWED = "RENEWED";
    /** Motivo de {@code SubscriptionExpired} y de {@code SubscriptionBillingPeriodChangeScheduled}. */
    public static final String REASON_EXPIRED = "EXPIRED";
    public static final String REASON_CHANGE_SCHEDULED = "CHANGE_SCHEDULED";
    public static final String REASON_CHANGE_CANCELLED = "CHANGE_CANCELLED";

    private SubscriptionEvents() {
    }

    /**
     * Estado de la suscripción de la organización.
     *
     * @param plan                 FREE, PLUS o PRO
     * @param status               TRIAL, ACTIVE o EXPIRED
     * @param billingPeriod        MONTHLY, ANNUAL o nulo en la prueba gratuita
     * @param maxActiveWorksites   obras activas que admite el plan; nulo = ilimitadas (Free en prueba y Pro)
     * @param nextBillingAt        próxima fecha de cobro; nula si no hay suscripción pagada vigente
     * @param pendingBillingPeriod modalidad programada para el siguiente periodo, o nula
     * @param reason               por qué se publica (TRIAL_STARTED, PAYMENT_CONFIRMED, RENEWED, EXPIRED, ...)
     */
    public record SubscriptionChanged(UUID organizationId, UUID subscriptionId, String plan, String status,
            Instant validUntil, String billingPeriod, Instant startedAt, Integer maxActiveWorksites,
            Instant currentPeriodStart, Instant nextBillingAt, String pendingBillingPeriod, Instant pendingChangeAt,
            String paymentProvider, String reason) {

        static SubscriptionChanged of(Subscription subscription, Integer maxActiveWorksites, String reason) {
            return new SubscriptionChanged(subscription.organizationId(), subscription.id(),
                    subscription.plan().name(), subscription.status().name(), subscription.validUntil(),
                    name(subscription.billingPeriod()), subscription.startedAt(), maxActiveWorksites,
                    subscription.currentPeriodStart(), subscription.nextBillingAt(),
                    name(subscription.pendingBillingPeriod()), subscription.pendingChangeAt(),
                    name(subscription.paymentProvider()), reason);
        }

        private static String name(Enum<?> value) {
            return value == null ? null : value.name();
        }
    }
}
