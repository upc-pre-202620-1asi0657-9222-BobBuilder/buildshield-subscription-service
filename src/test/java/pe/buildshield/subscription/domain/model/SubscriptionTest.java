package pe.buildshield.subscription.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubscriptionTest {

    private static final Instant START = Instant.parse("2026-10-08T15:00:00Z");
    private static final UUID ORGANIZATION = UUID.randomUUID();

    @Test
    void trial_starts_on_the_free_plan_with_all_features_until_the_end_of_the_period() {
        Subscription trial = Subscription.startTrial(ORGANIZATION, START, Duration.ofDays(14));

        assertThat(trial.id()).isNull();
        assertThat(trial.organizationId()).isEqualTo(ORGANIZATION);
        assertThat(trial.plan()).isEqualTo(PlanCode.FREE);
        assertThat(trial.status()).isEqualTo(SubscriptionStatus.TRIAL);
        assertThat(trial.billingPeriod()).isNull();
        assertThat(trial.paid()).isFalse();
        assertThat(trial.startedAt()).isEqualTo(START);
        assertThat(trial.trialEndsAt()).isEqualTo(START.plus(Duration.ofDays(14)));
        assertThat(trial.periodEnd()).isNull();
        assertThat(trial.validUntil()).isEqualTo(START.plus(Duration.ofDays(14)));
        assertThat(trial.currentPeriodStart()).isEqualTo(START);
        assertThat(trial.version()).isNull();
        assertThat(trial.readOnly(START)).isFalse();
        assertThat(trial.daysRemaining(START)).isEqualTo(14);
        assertThat(trial.daysRemaining(START.plus(Duration.ofHours(1)))).isEqualTo(14);
        assertThat(trial.daysRemaining(START.plus(Duration.ofDays(13)))).isEqualTo(1);
    }

    @Test
    void trial_needs_a_positive_length() {
        assertThatThrownBy(() -> Subscription.startTrial(ORGANIZATION, START, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Subscription.startTrial(ORGANIZATION, START, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Subscription.startTrial(ORGANIZATION, START, Duration.ofDays(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expires_only_once_the_end_date_passed_and_then_is_read_only() {
        Subscription trial = Subscription.startTrial(ORGANIZATION, START, Duration.ofDays(14));
        Instant end = START.plus(Duration.ofDays(14));

        assertThat(trial.isDue(end.minusSeconds(1))).isFalse();
        assertThat(trial.expireIfDue(end.minusSeconds(1))).isFalse();
        assertThat(trial.status()).isEqualTo(SubscriptionStatus.TRIAL);

        assertThat(trial.readOnly(end)).isTrue();
        assertThat(trial.daysRemaining(end)).isZero();
        assertThat(trial.expireIfDue(end)).isTrue();
        assertThat(trial.status()).isEqualTo(SubscriptionStatus.EXPIRED);
        assertThat(trial.expireIfDue(end.plusSeconds(60))).isFalse();
        assertThat(trial.isDue(end.plusSeconds(60))).isFalse();
    }

    @Test
    void restore_keeps_every_field() {
        UUID id = UUID.randomUUID();
        LocalDate end = LocalDate.parse("2026-11-07");
        Subscription restored = Subscription.restore(id, ORGANIZATION, PlanCode.PLUS, BillingPeriod.ANNUAL,
                SubscriptionStatus.ACTIVE, START, null, end, null, null, 3L);

        assertThat(restored.id()).isEqualTo(id);
        assertThat(restored.plan()).isEqualTo(PlanCode.PLUS);
        assertThat(restored.billingPeriod()).isEqualTo(BillingPeriod.ANNUAL);
        assertThat(restored.status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(restored.periodEnd()).isEqualTo(end);
        assertThat(restored.version()).isEqualTo(3L);
        assertThat(SubscriptionStatus.ACTIVE.label()).isEqualTo("Activa");
        assertThat(SubscriptionStatus.EXPIRED.label()).isEqualTo("Vencida");
        assertThatThrownBy(() -> Subscription.restore(null, ORGANIZATION, PlanCode.PLUS, null,
                SubscriptionStatus.ACTIVE, START, null, end, null, null, 0L)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Subscription.restore(id, ORGANIZATION, PlanCode.PLUS, null,
                SubscriptionStatus.ACTIVE, START, null, null, null, null, 0L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void an_expired_subscription_is_read_only_even_before_its_end_date() {
        Subscription expired = Subscription.restore(UUID.randomUUID(), ORGANIZATION, PlanCode.PRO,
                BillingPeriod.MONTHLY, SubscriptionStatus.EXPIRED, START, null, LocalDate.parse("2026-11-07"), null, null, 1L);

        assertThat(expired.readOnly(START.plusSeconds(10))).isTrue();
        assertThat(expired.daysRemaining(START.plusSeconds(10))).isZero();
    }

    @Test
    void a_confirmed_payment_activates_the_paid_plan_until_the_next_billing_date() {
        Subscription subscription = Subscription.startTrial(ORGANIZATION, START, Duration.ofDays(14));
        Instant paidAt = START.plus(Duration.ofDays(20));
        subscription.expireIfDue(paidAt);

        subscription.activate(PlanCode.PLUS, BillingPeriod.MONTHLY, paidAt, null);

        assertThat(subscription.status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(subscription.plan()).isEqualTo(PlanCode.PLUS);
        assertThat(subscription.paid()).isTrue();
        assertThat(subscription.billingPeriod()).isEqualTo(BillingPeriod.MONTHLY);
        assertThat(subscription.periodEnd()).isEqualTo(LocalDate.parse("2026-11-28"));
        assertThat(subscription.currentPeriodStart()).isEqualTo(Instant.parse("2026-10-28T00:00:00Z"));
        assertThat(subscription.validUntil()).isEqualTo(Instant.parse("2026-11-28T00:00:00Z"));
        assertThat(subscription.nextBillingAt()).isEqualTo(subscription.validUntil());
        assertThat(subscription.readOnly(paidAt)).isFalse();
        assertThat(subscription.startedAt()).isEqualTo(START);
    }

    @Test
    void only_plus_or_pro_with_a_billing_period_can_be_activated() {
        Subscription subscription = Subscription.startTrial(ORGANIZATION, START, Duration.ofDays(14));

        assertThatThrownBy(() -> subscription.activate(PlanCode.FREE, BillingPeriod.MONTHLY, START, null))
                .hasFieldOrPropertyWithValue("code", "INVALID_PAID_PLAN");
        assertThatThrownBy(() -> subscription.activate(PlanCode.PRO, null, START, null))
                .hasFieldOrPropertyWithValue("code", "INVALID_PAID_PLAN");
        assertThatThrownBy(() -> subscription.activate(null, BillingPeriod.ANNUAL, START, null))
                .hasFieldOrPropertyWithValue("code", "INVALID_PAID_PLAN");
        assertThat(subscription.nextBillingAt()).isNull();
        assertThat(subscription.pendingChangeAt()).isNull();
    }

    @Test
    void paid_subscriptions_keep_access_during_the_payment_grace_period() {
        Subscription subscription = paid(BillingPeriod.MONTHLY);
        Instant end = subscription.validUntil();

        assertThat(subscription.accessUntil()).isEqualTo(end.plus(Subscription.PAYMENT_GRACE));
        assertThat(subscription.readOnly(end.plusSeconds(60))).isFalse();
        assertThat(subscription.expireIfDue(end.plusSeconds(60))).isFalse();
        assertThat(subscription.renewsAutomatically(PaymentProvider.STRIPE, end.plusSeconds(60))).isFalse();
        assertThat(subscription.daysRemaining(end.plusSeconds(60))).isZero();
        assertThat(subscription.expireIfDue(end.plus(Subscription.PAYMENT_GRACE))).isTrue();
        assertThat(subscription.readOnly(end.plus(Subscription.PAYMENT_GRACE))).isTrue();
        assertThat(subscription.accessUntil()).isEqualTo(end);
    }

    @Test
    void a_renewal_starts_the_next_period_and_applies_the_scheduled_billing_period() {
        Subscription subscription = paid(BillingPeriod.MONTHLY);
        Instant end = subscription.validUntil();

        assertThat(subscription.scheduleBillingPeriodChange(BillingPeriod.ANNUAL, START))
                .isEqualTo(Subscription.BillingPeriodChange.SCHEDULED);
        assertThat(subscription.pendingChangeAt()).isEqualTo(end);
        assertThat(subscription.scheduleBillingPeriodChange(BillingPeriod.ANNUAL, START))
                .isEqualTo(Subscription.BillingPeriodChange.UNCHANGED);

        assertThat(subscription.renew(end.minusSeconds(120))).isTrue();
        assertThat(subscription.billingPeriod()).isEqualTo(BillingPeriod.ANNUAL);
        assertThat(subscription.pendingBillingPeriod()).isNull();
        assertThat(subscription.currentPeriodStart()).isEqualTo(end);
        assertThat(subscription.validUntil()).isEqualTo(BillingPeriod.ANNUAL.periodEnd(end));

        assertThat(subscription.renew(subscription.validUntil().plusSeconds(10))).isFalse();
    }

    @Test
    void a_late_renewal_of_an_expired_subscription_starts_when_it_is_paid() {
        Subscription subscription = paid(BillingPeriod.MONTHLY);
        Instant late = subscription.validUntil().plus(Duration.ofDays(10));
        subscription.expireIfDue(late);

        subscription.renew(late);

        assertThat(subscription.status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(subscription.currentPeriodStart()).isEqualTo(late);
    }

    @Test
    void the_trial_is_not_renewed_and_cannot_change_its_billing_period() {
        Subscription trial = Subscription.startTrial(ORGANIZATION, START, Duration.ofDays(14));

        assertThatThrownBy(() -> trial.renew(START)).hasFieldOrPropertyWithValue("code", "SUBSCRIPTION_NOT_ACTIVE");
        assertThatThrownBy(() -> trial.scheduleBillingPeriodChange(BillingPeriod.ANNUAL, START))
                .hasFieldOrPropertyWithValue("code", "SUBSCRIPTION_NOT_ACTIVE");
        Subscription expired = paid(BillingPeriod.ANNUAL);
        Instant afterGrace = expired.accessUntil();
        expired.expireIfDue(afterGrace);
        assertThatThrownBy(() -> expired.scheduleBillingPeriodChange(BillingPeriod.MONTHLY, afterGrace))
                .hasFieldOrPropertyWithValue("code", "SUBSCRIPTION_NOT_ACTIVE");
    }

    @Test
    void asking_for_the_current_period_cancels_the_scheduled_change() {
        Subscription subscription = paid(BillingPeriod.ANNUAL);

        assertThat(subscription.scheduleBillingPeriodChange(BillingPeriod.ANNUAL, START))
                .isEqualTo(Subscription.BillingPeriodChange.UNCHANGED);
        subscription.scheduleBillingPeriodChange(BillingPeriod.MONTHLY, START);
        assertThat(subscription.scheduleBillingPeriodChange(BillingPeriod.ANNUAL, START))
                .isEqualTo(Subscription.BillingPeriodChange.CANCELLED);
        assertThat(subscription.pendingBillingPeriod()).isNull();
        Instant end = subscription.validUntil();
        assertThat(subscription.renewsAutomatically(PaymentProvider.SIMULATED, end)).isTrue();
        assertThat(subscription.renewsAutomatically(PaymentProvider.SIMULATED, end.minusSeconds(1))).isFalse();
    }

    @Test
    void restore_keeps_the_scheduled_change_and_the_period_start_comes_from_the_period_end() {
        Subscription restored = Subscription.restore(UUID.randomUUID(), ORGANIZATION, PlanCode.PRO,
                BillingPeriod.ANNUAL, SubscriptionStatus.ACTIVE, START, START.plus(Duration.ofDays(14)),
                LocalDate.parse("2027-10-09"), BillingPeriod.MONTHLY, null, 2L);

        assertThat(restored.pendingBillingPeriod()).isEqualTo(BillingPeriod.MONTHLY);
        assertThat(restored.currentPeriodStart()).isEqualTo(Instant.parse("2026-10-09T00:00:00Z"));
        assertThat(restored.validUntil()).isEqualTo(Instant.parse("2027-10-09T00:00:00Z"));
        assertThat(Subscription.restore(UUID.randomUUID(), ORGANIZATION, PlanCode.FREE, null,
                SubscriptionStatus.TRIAL, START, START.plusSeconds(10), null, null, null, 0L).currentPeriodStart())
                .isEqualTo(START);
    }

    @Test
    void the_gateway_subscription_id_is_replaced_on_each_activation_and_kept_when_a_renewal_does_not_bring_it() {
        Subscription subscription = Subscription.startTrial(ORGANIZATION, START, Duration.ofDays(14));
        assertThat(subscription.gatewaySubscriptionId()).isNull();

        subscription.activate(PlanCode.PLUS, BillingPeriod.MONTHLY, START, "sub_1");
        assertThat(subscription.gatewaySubscriptionId()).isEqualTo("sub_1");

        subscription.renew(subscription.validUntil());
        assertThat(subscription.gatewaySubscriptionId()).isEqualTo("sub_1");
        subscription.renew(subscription.validUntil(), null);
        assertThat(subscription.gatewaySubscriptionId()).isEqualTo("sub_1");
        subscription.renew(subscription.validUntil(), "sub_2");
        assertThat(subscription.gatewaySubscriptionId()).isEqualTo("sub_2");

        // Un pago nuevo es otra suscripción en la pasarela: sin id se olvida el anterior y se busca por metadatos.
        subscription.activate(PlanCode.PRO, BillingPeriod.ANNUAL, START, null);
        assertThat(subscription.gatewaySubscriptionId()).isNull();
    }

    @Test
    void a_gateway_subscription_id_longer_than_the_column_is_not_stored() {
        String longest = "s".repeat(Subscription.MAX_GATEWAY_SUBSCRIPTION_ID_LENGTH);
        Subscription subscription = Subscription.startTrial(ORGANIZATION, START, Duration.ofDays(14));

        subscription.activate(PlanCode.PLUS, BillingPeriod.MONTHLY, START, longest);
        assertThat(subscription.gatewaySubscriptionId()).isEqualTo(longest);

        subscription.renew(subscription.validUntil(), longest + "x");
        assertThat(subscription.gatewaySubscriptionId()).isEqualTo(longest);

        subscription.activate(PlanCode.PLUS, BillingPeriod.MONTHLY, START, longest + "x");
        assertThat(subscription.gatewaySubscriptionId()).isNull();
    }

    private static Subscription paid(BillingPeriod period) {
        Subscription subscription = Subscription.startTrial(ORGANIZATION, START, Duration.ofDays(14));
        subscription.activate(PlanCode.PLUS, period, START, null);
        return subscription;
    }
}
