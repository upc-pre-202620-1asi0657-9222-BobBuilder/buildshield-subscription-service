package pe.buildshield.subscription.infrastructure.payment;

import org.springframework.web.util.UriComponentsBuilder;
import pe.buildshield.subscription.application.PaymentGateway;
import pe.buildshield.subscription.domain.model.BillingPeriod;
import pe.buildshield.subscription.domain.model.PaymentProvider;
import pe.buildshield.subscription.domain.model.Plan;

/**
 * Modo simulado explícito para desarrollo local sin cuenta de Stripe (perfil local y sin {@code STRIPE_SECRET_KEY}).
 * No llama a ninguna pasarela: la sesión es {@code cs_sim_...} y la URL lleva a una página de la web que simula el
 * Checkout; desde ahí el administrador completa el pago simulado, que se procesa como un webhook firmado. Nunca se
 * usa en producción: el perfil prod no permite la simulación.
 */
class SimulatedPaymentGateway implements PaymentGateway {

    private final PaymentsProperties properties;

    SimulatedPaymentGateway(PaymentsProperties properties) {
        this.properties = properties;
    }

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.SIMULATED;
    }

    @Override
    public CheckoutSession startCheckout(CheckoutRequest request) {
        String sessionId = "cs_sim_" + request.idempotencyKey().toString().replace("-", "");
        String url = UriComponentsBuilder.fromUriString(properties.simulatedCheckoutUrl())
                .queryParam("session_id", sessionId)
                .queryParam("plan", request.plan().code().name())
                .queryParam("billingPeriod", request.period().name())
                .build().toUriString();
        return new CheckoutSession(sessionId, url);
    }

    /** En el modo simulado el cambio solo vive en la suscripción local y se aplica en la renovación automática. */
    @Override
    public void scheduleBillingPeriodChange(String providerSubscriptionId, Plan plan, BillingPeriod current,
            BillingPeriod target) {
        // Nada que programar fuera de la base local.
    }
}
