package pe.buildshield.subscription.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentCheckoutTest {

    private static final Instant NOW = Instant.parse("2026-10-11T18:00:00Z");
    private static final UUID ORGANIZATION = UUID.randomUUID();

    @Test
    void an_open_session_completes_only_once() {
        UUID key = UUID.randomUUID();
        PaymentCheckout checkout = PaymentCheckout.open(key, ORGANIZATION, "cs_1", PlanCode.PLUS, BillingPeriod.ANNUAL,
                PaymentProvider.STRIPE, "https://checkout.stripe.com/c/pay/cs_1", NOW);

        assertThat(checkout.id()).isNull();
        assertThat(checkout.idempotencyKey()).isEqualTo(key);
        assertThat(checkout.organizationId()).isEqualTo(ORGANIZATION);
        assertThat(checkout.status()).isEqualTo(CheckoutStatus.OPEN);
        assertThat(checkout.openedAt()).isEqualTo(NOW);
        assertThat(checkout.checkoutUrl()).endsWith("cs_1");
        assertThat(checkout.version()).isNull();

        assertThat(checkout.complete(NOW.plusSeconds(60))).isTrue();
        assertThat(checkout.status()).isEqualTo(CheckoutStatus.COMPLETED);
        assertThat(checkout.completedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(checkout.complete(NOW.plusSeconds(90))).isFalse();
        assertThat(checkout.completedAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void a_recent_open_session_of_the_same_plan_and_period_is_reused() {
        PaymentCheckout checkout = PaymentCheckout.restore(UUID.randomUUID(), ORGANIZATION, UUID.randomUUID(), "cs_2",
                PlanCode.PRO, BillingPeriod.MONTHLY, PaymentProvider.SIMULATED, "http://web/pago", CheckoutStatus.OPEN,
                NOW, null, 0L);

        assertThat(checkout.reusableFor(PlanCode.PRO, BillingPeriod.MONTHLY, PaymentProvider.SIMULATED,
                NOW.plus(Duration.ofMinutes(29)))).isTrue();
        assertThat(checkout.reusableFor(PlanCode.PRO, BillingPeriod.MONTHLY, PaymentProvider.SIMULATED,
                NOW.plus(PaymentCheckout.REUSE_WINDOW))).isFalse();
        assertThat(checkout.reusableFor(PlanCode.PLUS, BillingPeriod.MONTHLY, PaymentProvider.SIMULATED, NOW)).isFalse();
        assertThat(checkout.reusableFor(PlanCode.PRO, BillingPeriod.ANNUAL, PaymentProvider.SIMULATED, NOW)).isFalse();
        assertThat(checkout.reusableFor(PlanCode.PRO, BillingPeriod.MONTHLY, PaymentProvider.STRIPE, NOW)).isFalse();
        checkout.complete(NOW);
        assertThat(checkout.reusableFor(PlanCode.PRO, BillingPeriod.MONTHLY, PaymentProvider.SIMULATED, NOW)).isFalse();
    }

    @Test
    void the_free_plan_is_never_paid_and_restore_needs_an_id() {
        assertThatThrownBy(() -> PaymentCheckout.open(UUID.randomUUID(), ORGANIZATION, "cs_3", PlanCode.FREE,
                BillingPeriod.MONTHLY, PaymentProvider.STRIPE, "u", NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentCheckout.restore(null, ORGANIZATION, UUID.randomUUID(), "cs_3", PlanCode.PLUS,
                BillingPeriod.MONTHLY, PaymentProvider.STRIPE, "u", CheckoutStatus.OPEN, NOW, null, 0L))
                .isInstanceOf(NullPointerException.class);
    }
}
