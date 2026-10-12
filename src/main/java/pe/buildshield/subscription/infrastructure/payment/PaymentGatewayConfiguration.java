package pe.buildshield.subscription.infrastructure.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.buildshield.subscription.application.PaymentGateway;

/**
 * Elige el adaptador de la pasarela: Stripe real, o el modo simulado si el perfil lo permite
 * ({@code buildshield.payments.simulation-enabled}, solo en local) y no hay {@code STRIPE_SECRET_KEY}.
 */
@Configuration(proxyBeanMethods = false)
class PaymentGatewayConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayConfiguration.class);

    @Bean
    PaymentGateway paymentGateway(PaymentsProperties properties, ObjectMapper json) {
        if (properties.simulated()) {
            log.warn("Pagos en MODO SIMULADO (desarrollo local, sin STRIPE_SECRET_KEY): no se cobra nada");
            return new SimulatedPaymentGateway(properties);
        }
        if (PaymentsProperties.blank(properties.stripe().secretKey())) {
            log.warn("STRIPE_SECRET_KEY no está configurada: el pago responderá 503 hasta configurarla");
        }
        if (PaymentsProperties.blank(properties.stripe().webhookSecret())) {
            log.warn("STRIPE_WEBHOOK_SECRET no está configurada: los webhooks de Stripe se rechazarán");
        }
        return new StripeAdapter(properties, json, StripeAdapter.defaultCircuitBreaker());
    }
}
