package pe.buildshield.subscription.infrastructure.payment;

import org.junit.jupiter.api.Test;
import pe.buildshield.subscription.application.PaymentGateway;
import pe.buildshield.subscription.domain.model.BillingPeriod;
import pe.buildshield.subscription.domain.model.Plan;
import pe.buildshield.subscription.domain.model.PlanCode;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StripeAdapterTest {

    @Test
    void payment_gateway_is_not_integrated_yet() {
        Plan plus = new Plan(PlanCode.PLUS, "Plus", "Hasta 5 obras", 5, false, new BigDecimal("299"),
                new BigDecimal("2990"), "PEN");

        assertThatThrownBy(() -> new StripeAdapter().startCheckout(UUID.randomUUID(), plus, BillingPeriod.MONTHLY))
                .isInstanceOf(PaymentGateway.UnavailableException.class)
                .hasMessageContaining("Sprint 4");
    }
}
