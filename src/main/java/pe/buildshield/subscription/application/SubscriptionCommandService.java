package pe.buildshield.subscription.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import pe.buildshield.subscription.domain.model.Subscription;
import pe.buildshield.subscription.domain.model.SubscriptionRepository;
import pe.buildshield.subscription.shared.messaging.IdempotentEventProcessor;
import pe.buildshield.subscription.shared.messaging.OutboxWriter;
import pe.buildshield.subscription.shared.tenant.TenantContext;
import pe.buildshield.subscription.shared.tenant.TenantInfo;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Casos de uso que cambian la suscripción: iniciar la prueba gratuita al registrarse la organización (US08) y
 * vencer las suscripciones cuya fecha de fin ya pasó. Cada cambio publica su evento por el outbox en la misma
 * transacción.
 */
@Service
public class SubscriptionCommandService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionCommandService.class);

    private final SubscriptionRepository subscriptions;
    private final OutboxWriter outbox;
    private final SubscriptionProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public SubscriptionCommandService(SubscriptionRepository subscriptions, OutboxWriter outbox,
            SubscriptionProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
        this.subscriptions = subscriptions;
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
        outbox.append(organizationId, null, SubscriptionEvents.SUBSCRIPTION_ACTIVATED, SubscriptionEvents.SUBSCRIPTION,
                saved.id(), SubscriptionEvents.SubscriptionChanged.of(saved));
        log.info("Prueba gratuita de la organización {} hasta {}", organizationId, saved.validUntil());
        return true;
    }

    /**
     * Vence las suscripciones de todas las organizaciones cuya fecha de fin ya pasó y publica
     * {@code SubscriptionExpired} (la cuenta queda en solo lectura). Cada organización va en su propia transacción.
     *
     * @return cuántas suscripciones vencieron
     */
    public int expireDue() {
        Instant now = clock.instant();
        List<UUID> due = TenantContext.callAsSystem(() -> transactions.execute(
                status -> subscriptions.findOrganizationsDue(now)));
        int expired = 0;
        for (UUID organizationId : due == null ? List.<UUID>of() : due) {
            TenantInfo system = new TenantInfo(organizationId, TenantContext.SYSTEM_USER_ID,
                    IdempotentEventProcessor.SYSTEM_ROLE);
            Boolean changed = TenantContext.callAs(system, () -> transactions.execute(status -> expire(now)));
            if (Boolean.TRUE.equals(changed)) {
                expired++;
            }
        }
        return expired;
    }

    private boolean expire(Instant now) {
        Subscription subscription = subscriptions.findCurrent().orElse(null);
        if (subscription == null || !subscription.expireIfDue(now)) {
            return false;
        }
        Subscription saved = subscriptions.save(subscription);
        outbox.append(saved.organizationId(), null, SubscriptionEvents.SUBSCRIPTION_EXPIRED,
                SubscriptionEvents.SUBSCRIPTION, saved.id(), SubscriptionEvents.SubscriptionChanged.of(saved));
        log.info("Suscripción de la organización {} vencida: solo lectura", saved.organizationId());
        return true;
    }
}
