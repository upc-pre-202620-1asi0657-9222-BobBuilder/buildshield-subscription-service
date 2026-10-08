package pe.buildshield.subscription.infrastructure.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pe.buildshield.subscription.application.PaymentGateway;
import pe.buildshield.subscription.domain.model.BillingPeriod;
import pe.buildshield.subscription.domain.model.Plan;

import java.util.UUID;

/**
 * Adaptador de Stripe para {@link PaymentGateway}. En este paso es solo el punto de extensión: no llama a Stripe
 * ni guarda datos de tarjeta. El cobro real (Checkout de Stripe y su webhook) se implementa en el Sprint 4.
 */
@Component
class StripeAdapter implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(StripeAdapter.class);

    @Override
    public CheckoutSession startCheckout(UUID organizationId, Plan plan, BillingPeriod period) {
        log.warn("Pago solicitado para la organización {} (plan {}, {}): la pasarela todavía no está integrada",
                organizationId, plan.code(), period);
        throw new UnavailableException("La pasarela de pagos se integra en el Sprint 4");
    }
}
