package pe.buildshield.subscription.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import pe.buildshield.subscription.application.PaymentGateway.CheckoutRequest;
import pe.buildshield.subscription.application.PaymentGateway.CheckoutSession;
import pe.buildshield.subscription.application.PaymentWebhooks.SignedWebhook;
import pe.buildshield.subscription.domain.model.BillingPeriod;
import pe.buildshield.subscription.domain.model.PaymentCheckout;
import pe.buildshield.subscription.domain.model.PaymentCheckoutRepository;
import pe.buildshield.subscription.domain.model.PaymentProvider;
import pe.buildshield.subscription.domain.model.Plan;
import pe.buildshield.subscription.domain.model.PlanCode;
import pe.buildshield.subscription.domain.model.PlanRepository;
import pe.buildshield.subscription.domain.model.Subscription;
import pe.buildshield.subscription.domain.model.Subscription.BillingPeriodChange;
import pe.buildshield.subscription.domain.model.SubscriptionRepository;
import pe.buildshield.subscription.domain.model.SubscriptionStatus;
import pe.buildshield.subscription.shared.error.ConflictException;
import pe.buildshield.subscription.shared.error.ResourceNotFoundException;
import pe.buildshield.subscription.shared.messaging.IdempotentEventProcessor;
import pe.buildshield.subscription.shared.messaging.OutboxWriter;
import pe.buildshield.subscription.shared.tenant.TenantContext;
import pe.buildshield.subscription.shared.tenant.TenantInfo;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Casos de uso que cambian la suscripción. Cada cambio publica su evento por el outbox en la misma transacción.
 *
 * <ul>
 *   <li>US08: iniciar la prueba gratuita al registrarse la organización y vencerla al terminar (solo lectura).</li>
 *   <li>US07 y US09: abrir la sesión de pago del plan Plus o Pro en la modalidad elegida (Stripe Checkout) y
 *       activar la suscripción cuando la pasarela confirma el pago por webhook firmado.</li>
 *   <li>US12: programar el cambio entre mensual y anual para el siguiente periodo de cobro.</li>
 * </ul>
 *
 * Las llamadas a la pasarela se hacen fuera de las transacciones de la base: una pasarela lenta no retiene
 * conexiones ni bloqueos.
 */
@Service
public class SubscriptionCommandService {

    public static final String SUBSCRIPTION_NOT_FOUND = "SUBSCRIPTION_NOT_FOUND";
    public static final String SUBSCRIPTION_ALREADY_ACTIVE = "SUBSCRIPTION_ALREADY_ACTIVE";
    public static final String CHECKOUT_NOT_FOUND = "CHECKOUT_NOT_FOUND";
    public static final String PAYMENT_SIMULATION_DISABLED = "PAYMENT_SIMULATION_DISABLED";
    public static final String PLAN_NOT_FOUND = "PLAN_NOT_FOUND";

    private static final Logger log = LoggerFactory.getLogger(SubscriptionCommandService.class);

    private final SubscriptionRepository subscriptions;
    private final PaymentCheckoutRepository checkouts;
    private final PlanRepository plans;
    private final PaymentGateway gateway;
    private final PaymentWebhooks webhooks;
    private final ProcessedPaymentEvents processedPayments;
    private final OutboxWriter outbox;
    private final SubscriptionProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public SubscriptionCommandService(SubscriptionRepository subscriptions, PaymentCheckoutRepository checkouts,
            PlanRepository plans, PaymentGateway gateway, PaymentWebhooks webhooks,
            ProcessedPaymentEvents processedPayments, OutboxWriter outbox, SubscriptionProperties properties,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.subscriptions = subscriptions;
        this.checkouts = checkouts;
        this.plans = plans;
        this.gateway = gateway;
        this.webhooks = webhooks;
        this.processedPayments = processedPayments;
        this.outbox = outbox;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Inicia la prueba gratuita de la organización del contexto (plan Free, todas las funciones). Si ya tiene
     * suscripción no hace nada: el evento de registro puede llegar más de una vez.
     *
     * @param registeredAt cuándo se registró la organización; la prueba cuenta desde ahí (nunca desde el futuro)
     * @return {@code true} si se creó la suscripción
     */
    @Transactional
    public boolean startTrial(Instant registeredAt) {
        UUID organizationId = TenantContext.require().organizationId();
        if (subscriptions.findCurrent().isPresent()) {
            return false;
        }
        Instant now = clock.instant();
        Instant start = registeredAt == null || registeredAt.isAfter(now) ? now : registeredAt;
        Subscription saved = subscriptions.save(Subscription.startTrial(organizationId, start,
                properties.trialLength()));
        announce(SubscriptionEvents.SUBSCRIPTION_ACTIVATED, saved, SubscriptionEvents.REASON_TRIAL_STARTED);
        log.info("Prueba gratuita de la organización {} hasta {}", organizationId, saved.validUntil());
        return true;
    }

    /**
     * Revisa las suscripciones de todas las organizaciones cuya fecha de fin ya pasó: vence las que no se pagaron
     * y publica {@code SubscriptionExpired} (la cuenta queda en solo lectura). En el modo simulado, que no tiene
     * pasarela que cobre, renueva la suscripción pagada (y aplica el cambio de modalidad programado). Cada
     * organización va en su propia transacción.
     *
     * @return cuántas suscripciones cambiaron (vencidas o renovadas)
     */
    public int expireDue() {
        Instant now = clock.instant();
        List<UUID> due = TenantContext.callAsSystem(() -> transactions.execute(
                status -> subscriptions.findOrganizationsDue(now)));
        int changed = 0;
        for (UUID organizationId : due == null ? List.<UUID>of() : due) {
            Boolean result = TenantContext.callAs(system(organizationId),
                    () -> transactions.execute(status -> expireOrRenew(now)));
            if (Boolean.TRUE.equals(result)) {
                changed++;
            }
        }
        return changed;
    }

    private boolean expireOrRenew(Instant now) {
        Subscription subscription = subscriptions.findCurrent().orElse(null);
        if (subscription == null) {
            return false;
        }
        if (subscription.renewsAutomatically(now)) {
            subscription.renew(subscription.validUntil());
            Subscription saved = subscriptions.save(subscription);
            announce(SubscriptionEvents.SUBSCRIPTION_ACTIVATED, saved, SubscriptionEvents.REASON_RENEWED);
            log.info("Suscripción simulada de la organización {} renovada hasta {}", saved.organizationId(),
                    saved.validUntil());
            return true;
        }
        if (!subscription.expireIfDue(now)) {
            return false;
        }
        Subscription saved = subscriptions.save(subscription);
        announce(SubscriptionEvents.SUBSCRIPTION_EXPIRED, saved, SubscriptionEvents.REASON_EXPIRED);
        log.info("Suscripción de la organización {} vencida: solo lectura", saved.organizationId());
        return true;
    }

    /**
     * US07 y US09: abre la sesión de pago del plan y la modalidad elegidos para la organización del contexto. Si
     * hay una sesión abierta reciente del mismo plan y modalidad, la reutiliza (doble clic o recarga de la página).
     * La clave de idempotencia de la pasarela es el id local de la sesión.
     *
     * @throws ConflictException             si la organización ya tiene una suscripción pagada vigente
     * @throws PaymentGateway.UnavailableException si la pasarela no responde (503)
     */
    public CheckoutStarted startCheckout(PlanCode planCode, BillingPeriod period) {
        UUID organizationId = TenantContext.require().organizationId();
        Instant now = clock.instant();
        Plan plan = plan(planCode).requirePayable();
        PaymentProvider provider = gateway.provider();
        PaymentCheckout reusable = transactions.execute(status -> {
            Subscription current = currentSubscription();
            if (current.status() == SubscriptionStatus.ACTIVE && !current.readOnly(now)) {
                throw new ConflictException(SUBSCRIPTION_ALREADY_ACTIVE, "Tu organización ya tiene el plan "
                        + current.plan() + " pagado y vigente; para cambiar entre mensual y anual usa el cambio de "
                        + "modalidad");
            }
            return checkouts.findOpen().stream()
                    .filter(checkout -> checkout.reusableFor(planCode, period, provider, now))
                    .findFirst().orElse(null);
        });
        if (reusable != null) {
            return new CheckoutStarted(reusable, plan, true);
        }
        UUID idempotencyKey = UUID.randomUUID();
        CheckoutSession session = gateway.startCheckout(new CheckoutRequest(idempotencyKey, organizationId, plan,
                period));
        PaymentCheckout saved = transactions.execute(status -> checkouts.save(PaymentCheckout.open(idempotencyKey,
                organizationId, session.sessionId(), planCode, period, provider, session.redirectUrl(), now)));
        log.info("Sesión de pago {} ({}, {}) abierta para la organización {}", session.sessionId(), planCode, period,
                organizationId);
        return new CheckoutStarted(saved, plan, false);
    }

    /**
     * Modo simulado (desarrollo local sin Stripe): completa el pago de la sesión. Arma el mismo evento firmado que
     * enviaría Stripe y lo procesa por el camino del webhook.
     */
    public WebhookResult completeSimulatedPayment(String sessionId) {
        if (gateway.provider() != PaymentProvider.SIMULATED) {
            throw new ConflictException(PAYMENT_SIMULATION_DISABLED,
                    "El pago simulado solo existe en desarrollo local, sin clave de Stripe configurada");
        }
        PaymentCheckout checkout = transactions.execute(status -> checkouts.findBySessionId(sessionId))
                .orElseThrow(() -> new ResourceNotFoundException(CHECKOUT_NOT_FOUND,
                        "La sesión de pago " + sessionId + " no existe"));
        SignedWebhook webhook = webhooks.simulateCheckoutCompleted(checkout, clock.instant());
        return receiveWebhook(webhook.payload(), webhook.signatureHeader());
    }

    /**
     * Webhook de la pasarela (T74): verifica la firma, descarta los eventos repetidos por su id y aplica el efecto
     * en una transacción con la organización del pago. {@code checkout.session.completed} activa la suscripción
     * con el plan y la modalidad de la sesión guardada (no con los que vengan en el evento); {@code invoice.paid}
     * de una renovación empieza el siguiente periodo. Los demás eventos se registran y se ignoran.
     *
     * @throws InvalidPaymentWebhookException si la firma no es válida (400)
     */
    public WebhookResult receiveWebhook(String payload, String signatureHeader) {
        PaymentEvent event = webhooks.verify(payload, signatureHeader);
        return switch (event.type()) {
            case CHECKOUT_COMPLETED -> event.organizationId() == null
                    ? ignore(event, "la sesión no trae la organización")
                    : inOrganization(event.organizationId(), () -> checkoutCompleted(event));
            case RENEWAL_PAID -> renewalOrganization(event)
                    .map(organizationId -> inOrganization(organizationId, () -> renewalPaid(event)))
                    .orElseGet(() -> ignore(event, "la suscripción de la pasarela no es de ninguna organización"));
            case IGNORED -> ignore(event, "tipo de evento sin efecto");
        };
    }

    private WebhookResult checkoutCompleted(PaymentEvent event) {
        if (!processedPayments.markProcessed(event.eventId(), event.providerType())) {
            return WebhookResult.DUPLICATE;
        }
        PaymentCheckout checkout = checkouts.findBySessionId(event.sessionId()).orElse(null);
        if (checkout == null || !checkout.complete(clock.instant())) {
            log.warn("Evento {}: la sesión {} no existe o ya estaba pagada", event.eventId(), event.sessionId());
            return WebhookResult.IGNORED;
        }
        checkouts.save(checkout);
        Subscription subscription = currentSubscription();
        subscription.activate(checkout.plan(), checkout.billingPeriod(), clock.instant(), checkout.provider(),
                event.customerId(), event.providerSubscriptionId());
        Subscription saved = subscriptions.save(subscription);
        announce(SubscriptionEvents.SUBSCRIPTION_ACTIVATED, saved, SubscriptionEvents.REASON_PAYMENT_CONFIRMED);
        log.info("Pago confirmado: organización {} con el plan {} {} hasta {}", saved.organizationId(), saved.plan(),
                saved.billingPeriod(), saved.validUntil());
        return WebhookResult.PROCESSED;
    }

    private WebhookResult renewalPaid(PaymentEvent event) {
        if (!processedPayments.markProcessed(event.eventId(), event.providerType())) {
            return WebhookResult.DUPLICATE;
        }
        Subscription subscription = currentSubscription();
        boolean periodChanged = subscription.renew(clock.instant());
        Subscription saved = subscriptions.save(subscription);
        announce(SubscriptionEvents.SUBSCRIPTION_ACTIVATED, saved, SubscriptionEvents.REASON_RENEWED);
        log.info("Renovación cobrada: organización {} hasta {}{}", saved.organizationId(), saved.validUntil(),
                periodChanged ? " con la nueva modalidad " + saved.billingPeriod() : "");
        return WebhookResult.PROCESSED;
    }

    private Optional<UUID> renewalOrganization(PaymentEvent event) {
        if (event.providerSubscriptionId() == null) {
            return Optional.empty();
        }
        return TenantContext.callAsSystem(() -> transactions.execute(status ->
                subscriptions.findOrganizationByPaymentSubscriptionId(event.providerSubscriptionId())));
    }

    private WebhookResult ignore(PaymentEvent event, String why) {
        Boolean first = TenantContext.callAsSystem(() -> transactions.execute(status ->
                processedPayments.markProcessed(event.eventId(), event.providerType())));
        log.debug("Evento {} ({}) ignorado: {}", event.eventId(), event.providerType(), why);
        return Boolean.TRUE.equals(first) ? WebhookResult.IGNORED : WebhookResult.DUPLICATE;
    }

    private WebhookResult inOrganization(UUID organizationId, java.util.function.Supplier<WebhookResult> work) {
        return TenantContext.callAs(system(organizationId), () -> transactions.execute(status -> work.get()));
    }

    /**
     * US12: cambia entre mensual y anual a partir del siguiente periodo de cobro. Pedir la modalidad actual cancela
     * un cambio programado. Con Stripe, el cambio se programa primero en la pasarela (fuera de la transacción) y
     * después se guarda.
     */
    public BillingPeriodChange changeBillingPeriod(BillingPeriod target) {
        Instant now = clock.instant();
        Subscription preview = transactions.execute(status -> currentSubscription());
        BillingPeriod current = preview.billingPeriod();
        BillingPeriodChange outcome = preview.scheduleBillingPeriodChange(target, now);
        if (outcome == BillingPeriodChange.UNCHANGED) {
            return outcome;
        }
        if (preview.paymentProvider() == PaymentProvider.STRIPE) {
            gateway.scheduleBillingPeriodChange(preview.paymentSubscriptionId(), plan(preview.plan()), current, target);
        }
        return transactions.execute(status -> {
            Subscription subscription = currentSubscription();
            BillingPeriodChange applied = subscription.scheduleBillingPeriodChange(target, now);
            Subscription saved = subscriptions.save(subscription);
            announce(SubscriptionEvents.BILLING_PERIOD_CHANGE_SCHEDULED, saved,
                    applied == BillingPeriodChange.CANCELLED ? SubscriptionEvents.REASON_CHANGE_CANCELLED
                            : SubscriptionEvents.REASON_CHANGE_SCHEDULED);
            log.info("Organización {}: modalidad {} {}", saved.organizationId(), target, applied);
            return applied;
        });
    }

    private void announce(String eventType, Subscription subscription, String reason) {
        outbox.append(subscription.organizationId(), null, eventType, SubscriptionEvents.SUBSCRIPTION,
                subscription.id(), SubscriptionEvents.SubscriptionChanged.of(subscription,
                        plans.findByCode(subscription.plan()).map(Plan::maxActiveWorksites).orElse(null), reason));
    }

    private Subscription currentSubscription() {
        return subscriptions.findCurrent().orElseThrow(() -> new ResourceNotFoundException(SUBSCRIPTION_NOT_FOUND,
                "La organización todavía no tiene suscripción"));
    }

    private Plan plan(PlanCode code) {
        return plans.findByCode(code).orElseThrow(() -> new ResourceNotFoundException(PLAN_NOT_FOUND,
                "El plan " + code + " no existe"));
    }

    private static TenantInfo system(UUID organizationId) {
        return new TenantInfo(organizationId, TenantContext.SYSTEM_USER_ID, IdempotentEventProcessor.SYSTEM_ROLE);
    }

    /** Sesión de pago lista para redirigir al cliente; {@code reused} si era una sesión abierta reciente. */
    public record CheckoutStarted(PaymentCheckout checkout, Plan plan, boolean reused) {
    }

    /** Qué pasó con un webhook. */
    public enum WebhookResult {
        PROCESSED, DUPLICATE, IGNORED
    }
}
