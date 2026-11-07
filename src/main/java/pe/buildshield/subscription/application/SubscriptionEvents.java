package pe.buildshield.subscription.application;

import pe.buildshield.subscription.domain.model.Payment;
import pe.buildshield.subscription.domain.model.PaymentProvider;
import pe.buildshield.subscription.domain.model.PaymentStatus;
import pe.buildshield.subscription.domain.model.Subscription;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Eventos de integración que publica subscription en {@code buildshield.subscription} (payload del sobre). iam los
 * consume para {@code organization_access} y lee {@code organizationId}, {@code plan}, {@code status} y
 * {@code validUntil}: esos nombres no se cambian. organization lee además {@code maxActiveWorksites} para aplicar el
 * límite de obras activas del plan. Los payloads de suscripción y pagos llevan además {@code periodEnd} (TAREA.md).
 */
public final class SubscriptionEvents {

    public static final String SUBSCRIPTION_ACTIVATED = "SubscriptionActivated";
    public static final String SUBSCRIPTION_EXPIRED = "SubscriptionExpired";
    public static final String BILLING_PERIOD_CHANGE_SCHEDULED = "SubscriptionBillingPeriodChangeScheduled";
    public static final String PAYMENT_SUCCEEDED = "PaymentSucceeded";
    public static final String PAYMENT_FAILED = "PaymentFailed";
    public static final String SUBSCRIPTION = "SUBSCRIPTION";
    public static final String PAYMENT = "PAYMENT";

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
     * @param periodEnd            fin del periodo actual en fecha, o nulo en prueba
     */
    public record SubscriptionChanged(UUID organizationId, UUID subscriptionId, String plan, String status,
            Instant validUntil, String billingPeriod, Instant startedAt, Integer maxActiveWorksites,
            Instant currentPeriodStart, Instant nextBillingAt, String pendingBillingPeriod, Instant pendingChangeAt,
            String paymentProvider, String reason, LocalDate periodEnd) {

        static SubscriptionChanged of(Subscription subscription, Integer maxActiveWorksites,
                PaymentProvider provider, String reason) {
            return new SubscriptionChanged(subscription.organizationId(), subscription.id(),
                    subscription.plan().name(), subscription.status().name(), subscription.validUntil(),
                    name(subscription.billingPeriod()), subscription.startedAt(), maxActiveWorksites,
                    subscription.currentPeriodStart(), subscription.nextBillingAt(),
                    name(subscription.pendingBillingPeriod()), subscription.pendingChangeAt(),
                    name(subscription.paid() ? provider : null), reason, subscription.periodEnd());
        }

        private static String name(Enum<?> value) {
            return value == null ? null : value.name();
        }
    }

    /**
     * Resultado del cobro de un plan o renovación (TAREA.md línea 77).
     *
     * @param organizationId     organización
     * @param subscriptionId     suscripción asociada
     * @param paymentId          identificador del pago
     * @param plan               FREE, PLUS o PRO
     * @param status             PAID o FAILED
     * @param maxActiveWorksites límite de obras del plan
     * @param periodEnd          fin del periodo de cobro
     * @param amount             monto cobrado
     * @param gatewayChargeId    id de cobro en pasarela (in_...)
     * @param failureReason      motivo de rechazo o fallo si falló
     */
    public record PaymentResult(UUID organizationId, UUID subscriptionId, UUID paymentId, String plan,
            String status, Integer maxActiveWorksites, LocalDate periodEnd, BigDecimal amount,
            String gatewayChargeId, String failureReason) {

        public static PaymentResult succeeded(Subscription subscription, Payment payment, Integer maxActiveWorksites) {
            return new PaymentResult(subscription.organizationId(), subscription.id(), payment.id(),
                    subscription.plan().name(), payment.status().name(), maxActiveWorksites,
                    subscription.periodEnd(), payment.amount(), payment.gatewayChargeId(), null);
        }

        public static PaymentResult failed(Subscription subscription, Payment payment, Integer maxActiveWorksites,
                String failureReason) {
            return new PaymentResult(subscription.organizationId(), subscription.id(),
                    payment != null ? payment.id() : null,
                    subscription.plan().name(), PaymentStatus.FAILED.name(), maxActiveWorksites,
                    subscription.periodEnd(), payment != null ? payment.amount() : null,
                    payment != null ? payment.gatewayChargeId() : null, failureReason);
        }
    }
}
