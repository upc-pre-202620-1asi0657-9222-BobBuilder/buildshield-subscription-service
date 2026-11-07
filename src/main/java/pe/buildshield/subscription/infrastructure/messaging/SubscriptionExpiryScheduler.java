package pe.buildshield.subscription.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pe.buildshield.subscription.application.SubscriptionCommandService;

/** Revisa periódicamente las suscripciones vencidas y publica {@code SubscriptionExpired} por el outbox. */
@Component
class SubscriptionExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionExpiryScheduler.class);

    private final SubscriptionCommandService commands;

    SubscriptionExpiryScheduler(SubscriptionCommandService commands) {
        this.commands = commands;
    }

    @Scheduled(fixedDelayString = "${buildshield.subscription.expiry-check-interval:PT1M}", initialDelay = 10_000)
    void expireDue() {
        try {
            int expired = commands.expireDue();
            if (expired > 0) {
                log.info("{} suscripciones vencidas", expired);
            }
        } catch (RuntimeException ex) {
            log.warn("No se pudo revisar el vencimiento de las suscripciones: {}", ex.getMessage());
        }
    }
}
