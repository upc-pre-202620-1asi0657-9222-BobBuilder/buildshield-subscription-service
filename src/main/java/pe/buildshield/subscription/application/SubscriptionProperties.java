package pe.buildshield.subscription.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuración de Suscripción y Pagos ({@code buildshield.subscription.*}).
 *
 * @param trialDays            días de prueba gratuita desde el registro de la organización
 * @param expiryCheckInterval  cada cuánto se buscan suscripciones vencidas
 */
@ConfigurationProperties(prefix = "buildshield.subscription")
public record SubscriptionProperties(Integer trialDays, Duration expiryCheckInterval) {

    public static final int DEFAULT_TRIAL_DAYS = 14;

    public SubscriptionProperties {
        trialDays = trialDays == null || trialDays < 1 ? DEFAULT_TRIAL_DAYS : trialDays;
        expiryCheckInterval = expiryCheckInterval == null ? Duration.ofMinutes(1) : expiryCheckInterval;
    }

    public Duration trialLength() {
        return Duration.ofDays(trialDays);
    }
}
