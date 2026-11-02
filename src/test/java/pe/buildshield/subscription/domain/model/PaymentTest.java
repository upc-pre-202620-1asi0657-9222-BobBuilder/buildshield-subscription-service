package pe.buildshield.subscription.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    private static final Instant NOW = Instant.parse("2026-10-11T18:00:00Z");
    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID SUBSCRIPTION = UUID.randomUUID();
    private static final BigDecimal AMOUNT = new BigDecimal("2990.00");

    @Test
    void a_pending_payment_completes_only_once() {
        UUID key = UUID.randomUUID();
        Payment payment = Payment.open(SUBSCRIPTION, ORGANIZATION, key, AMOUNT, NOW);

        assertThat(payment.id()).isNull();
        assertThat(payment.idempotencyKey()).isEqualTo(key);
        assertThat(payment.organizationId()).isEqualTo(ORGANIZATION);
        assertThat(payment.subscriptionId()).isEqualTo(SUBSCRIPTION);
        assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.createdAt()).isEqualTo(NOW);
        assertThat(payment.amount()).isEqualByComparingTo(AMOUNT);
        assertThat(payment.gatewayChargeId()).isNull();
        assertThat(payment.failureReason()).isNull();

        assertThat(payment.complete("in_1")).isTrue();
        assertThat(payment.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.gatewayChargeId()).isEqualTo("in_1");
        assertThat(payment.complete("in_2")).isFalse();
        assertThat(payment.gatewayChargeId()).isEqualTo("in_1");
    }

    @Test
    void a_recent_pending_payment_of_the_same_amount_is_reused() {
        Payment payment = Payment.restore(UUID.randomUUID(), SUBSCRIPTION, ORGANIZATION, UUID.randomUUID(), null,
                new BigDecimal("699.00"), PaymentStatus.PENDING, null, NOW);

        assertThat(payment.reusableFor(new BigDecimal("699"), NOW.plus(Duration.ofMinutes(29)))).isTrue();
        assertThat(payment.reusableFor(new BigDecimal("699"), NOW.plus(Payment.REUSE_WINDOW))).isFalse();
        assertThat(payment.reusableFor(new BigDecimal("299"), NOW)).isFalse();
        assertThat(payment.matchesPending(new BigDecimal("699.00"))).isTrue();
        payment.complete(null);
        assertThat(payment.reusableFor(new BigDecimal("699"), NOW)).isFalse();
        assertThat(payment.matchesPending(new BigDecimal("699"))).isFalse();
    }

    @Test
    void a_renewal_is_born_paid_and_restore_needs_an_id() {
        Payment renewal = Payment.paid(SUBSCRIPTION, ORGANIZATION, UUID.randomUUID(), "in_9", AMOUNT, NOW);

        assertThat(renewal.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(renewal.gatewayChargeId()).isEqualTo("in_9");
        assertThat(renewal.complete("in_10")).isFalse();
        assertThatThrownBy(() -> Payment.restore(null, SUBSCRIPTION, ORGANIZATION, UUID.randomUUID(), null, AMOUNT,
                PaymentStatus.PENDING, null, NOW)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void a_charge_id_longer_than_the_column_is_not_stored() {
        Payment payment = Payment.open(SUBSCRIPTION, ORGANIZATION, UUID.randomUUID(), AMOUNT, NOW);

        payment.complete("in_" + "x".repeat(Payment.MAX_CHARGE_ID_LENGTH));

        assertThat(payment.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.gatewayChargeId()).isNull();
    }
}
