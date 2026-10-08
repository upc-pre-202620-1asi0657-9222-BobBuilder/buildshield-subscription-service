package pe.buildshield.subscription.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
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
        assertThat(trial.startedAt()).isEqualTo(START);
        assertThat(trial.validUntil()).isEqualTo(START.plus(Duration.ofDays(14)));
        assertThat(trial.expiredAt()).isNull();
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
        assertThat(trial.expiredAt()).isEqualTo(end);
        assertThat(trial.expireIfDue(end.plusSeconds(60))).isFalse();
        assertThat(trial.isDue(end.plusSeconds(60))).isFalse();
    }

    @Test
    void restore_keeps_every_field() {
        UUID id = UUID.randomUUID();
        Instant until = START.plus(Duration.ofDays(30));
        Subscription restored = Subscription.restore(id, ORGANIZATION, PlanCode.PLUS, BillingPeriod.ANNUAL,
                SubscriptionStatus.ACTIVE, START, until, null, 3L);

        assertThat(restored.id()).isEqualTo(id);
        assertThat(restored.plan()).isEqualTo(PlanCode.PLUS);
        assertThat(restored.billingPeriod()).isEqualTo(BillingPeriod.ANNUAL);
        assertThat(restored.status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(restored.version()).isEqualTo(3L);
        assertThat(SubscriptionStatus.ACTIVE.label()).isEqualTo("Activa");
        assertThat(SubscriptionStatus.EXPIRED.label()).isEqualTo("Vencida");
        assertThatThrownBy(() -> Subscription.restore(null, ORGANIZATION, PlanCode.PLUS, null,
                SubscriptionStatus.ACTIVE, START, until, null, 0L)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void an_expired_subscription_is_read_only_even_before_its_end_date() {
        Subscription expired = Subscription.restore(UUID.randomUUID(), ORGANIZATION, PlanCode.PRO,
                BillingPeriod.MONTHLY, SubscriptionStatus.EXPIRED, START, START.plus(Duration.ofDays(30)), START, 1L);

        assertThat(expired.readOnly(START.plusSeconds(10))).isTrue();
        assertThat(expired.daysRemaining(START.plusSeconds(10))).isZero();
    }
}
