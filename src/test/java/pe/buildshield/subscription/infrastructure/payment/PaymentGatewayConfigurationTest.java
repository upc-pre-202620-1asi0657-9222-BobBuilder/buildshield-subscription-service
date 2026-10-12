package pe.buildshield.subscription.infrastructure.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import pe.buildshield.subscription.application.PaymentGateway;
import pe.buildshield.subscription.domain.model.BillingPeriod;
import pe.buildshield.subscription.domain.model.PaymentProvider;
import pe.buildshield.subscription.domain.model.Plan;
import pe.buildshield.subscription.domain.model.PlanCode;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** El modo simulado es explícito: solo con la simulación permitida (perfil local) y sin clave de Stripe. */
class PaymentGatewayConfigurationTest {

    private final PaymentGatewayConfiguration configuration = new PaymentGatewayConfiguration();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void simulation_only_when_allowed_and_without_stripe_key() {
        assertThat(configuration.paymentGateway(properties(true, null, null), json).provider())
                .isEqualTo(PaymentProvider.SIMULATED);
        assertThat(configuration.paymentGateway(properties(true, "sk_test_x", "whsec_x"), json).provider())
                .isEqualTo(PaymentProvider.STRIPE);
        assertThat(configuration.paymentGateway(properties(false, null, null), json).provider())
                .isEqualTo(PaymentProvider.STRIPE);
    }

    @Test
    void the_simulated_checkout_points_to_the_web_page_with_the_session() {
        PaymentGateway simulated = configuration.paymentGateway(properties(true, null, null), json);
        UUID key = UUID.fromString("9f1c2d3e-4a5b-4c6d-8e7f-9a0b1c2d3e4f");
        Plan pro = new Plan(PlanCode.PRO, "Pro", "Ilimitadas", null, false, BigDecimal.ONE, BigDecimal.TEN, "PEN");

        PaymentGateway.CheckoutSession session = simulated.startCheckout(new PaymentGateway.CheckoutRequest(key,
                UUID.randomUUID(), pro, BillingPeriod.MONTHLY));
        simulated.scheduleBillingPeriodChange("sub_sim", pro, BillingPeriod.MONTHLY, BillingPeriod.ANNUAL);

        assertThat(session.sessionId()).isEqualTo("cs_sim_9f1c2d3e4a5b4c6d8e7f9a0b1c2d3e4f");
        assertThat(session.redirectUrl()).isEqualTo("http://localhost:4200/suscripcion/pago-simulado"
                + "?session_id=cs_sim_9f1c2d3e4a5b4c6d8e7f9a0b1c2d3e4f&plan=PRO&billingPeriod=MONTHLY");
    }

    @Test
    void properties_have_safe_defaults_and_the_simulated_secret_only_in_simulation() {
        PaymentsProperties defaults = new PaymentsProperties(false, " ", null, "", null);
        assertThat(defaults.successUrl()).startsWith("http://localhost:4200/suscripcion");
        assertThat(defaults.cancelUrl()).contains("cancelado");
        assertThat(defaults.stripe().apiBaseUrl()).isEqualTo("https://api.stripe.com");
        assertThat(defaults.stripe().timeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(defaults.stripe().webhookTolerance()).isEqualTo(Duration.ofMinutes(5));
        assertThat(defaults.effectiveWebhookSecret()).isNull();
        assertThat(properties(true, null, null).effectiveWebhookSecret())
                .isEqualTo(PaymentsProperties.SIMULATED_WEBHOOK_SECRET);
        assertThat(properties(true, null, "whsec_propio").effectiveWebhookSecret()).isEqualTo("whsec_propio");
        PaymentsProperties.Prices prices = new PaymentsProperties.Prices("a", "b", "c", " ");
        assertThat(prices.priceId(PlanCode.PLUS, BillingPeriod.MONTHLY)).isEqualTo("a");
        assertThat(prices.priceId(PlanCode.PLUS, BillingPeriod.ANNUAL)).isEqualTo("b");
        assertThat(prices.priceId(PlanCode.PRO, BillingPeriod.MONTHLY)).isEqualTo("c");
        assertThat(prices.priceId(PlanCode.PRO, BillingPeriod.ANNUAL)).isNull();
        assertThat(prices.priceId(PlanCode.FREE, BillingPeriod.ANNUAL)).isNull();
    }

    private static PaymentsProperties properties(boolean simulation, String secretKey, String webhookSecret) {
        return new PaymentsProperties(simulation, null, null, null,
                new PaymentsProperties.Stripe(secretKey, webhookSecret, null, null, null, null));
    }
}
