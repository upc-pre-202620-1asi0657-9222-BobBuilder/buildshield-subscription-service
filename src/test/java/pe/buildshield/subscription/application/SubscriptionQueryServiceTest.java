package pe.buildshield.subscription.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pe.buildshield.subscription.domain.model.BillingPeriod;
import pe.buildshield.subscription.domain.model.PaymentProvider;
import pe.buildshield.subscription.domain.model.Plan;
import pe.buildshield.subscription.domain.model.PlanCode;
import pe.buildshield.subscription.domain.model.PlanRepository;
import pe.buildshield.subscription.domain.model.Subscription;
import pe.buildshield.subscription.domain.model.SubscriptionStatus;
import pe.buildshield.subscription.shared.error.ResourceNotFoundException;
import pe.buildshield.subscription.shared.tenant.TenantContext;
import pe.buildshield.subscription.shared.tenant.TenantInfo;
import pe.buildshield.subscription.support.MutableClock;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubscriptionQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-08T15:00:00Z");

    private final Plan free = new Plan(PlanCode.FREE, "Free", "Prueba", null, true, BigDecimal.ZERO, BigDecimal.ZERO,
            "PEN");
    private final Plan plus = new Plan(PlanCode.PLUS, "Plus", "Hasta 5 obras", 5, false, new BigDecimal("299"),
            new BigDecimal("2990"), "PEN");
    private final PlanRepository plans = new PlanRepository() {
        @Override
        public List<Plan> findAll() {
            return List.of(free, plus);
        }

        @Override
        public Optional<Plan> findByCode(PlanCode code) {
            return findAll().stream().filter(plan -> plan.code() == code).findFirst();
        }
    };
    private final InMemorySubscriptions subscriptions = new InMemorySubscriptions();
    private final MutableClock clock = new MutableClock(NOW);
    private final SubscriptionQueryService queries = new SubscriptionQueryService(plans, subscriptions,
            new SubscriptionProperties(null, null), clock);
    private final UUID organization = UUID.randomUUID();

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void lists_the_plans_and_the_trial_length() {
        assertThat(queries.plans()).extracting(Plan::code).containsExactly(PlanCode.FREE, PlanCode.PLUS);
        assertThat(queries.trialDays()).isEqualTo(SubscriptionProperties.DEFAULT_TRIAL_DAYS);
    }

    @Test
    void current_shows_the_trial_with_its_remaining_days() {
        subscriptions.save(Subscription.startTrial(organization, NOW, Duration.ofDays(14)));
        clock.advance(Duration.ofDays(4));

        var view = TenantContext.callAs(admin(), queries::current);

        assertThat(view.plan()).isEqualTo(free);
        assertThat(view.status()).isEqualTo(SubscriptionStatus.TRIAL);
        assertThat(view.daysRemaining()).isEqualTo(10);
        assertThat(view.readOnly()).isFalse();
        assertThat(view.organizationId()).isEqualTo(organization);
        assertThat(view.validUntil()).isEqualTo(NOW.plus(Duration.ofDays(14)));
    }

    @Test
    void current_shows_expired_and_read_only_once_the_end_date_passed() {
        subscriptions.save(Subscription.startTrial(organization, NOW, Duration.ofDays(14)));
        clock.advance(Duration.ofDays(15));

        var view = TenantContext.callAs(admin(), queries::current);

        assertThat(view.status()).isEqualTo(SubscriptionStatus.EXPIRED);
        assertThat(view.readOnly()).isTrue();
        assertThat(view.daysRemaining()).isZero();
    }

    @Test
    void without_subscription_current_is_not_found() {
        assertThatThrownBy(() -> TenantContext.callAs(admin(), queries::current))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("todavía no tiene suscripción");
    }

    @Test
    void properties_fall_back_to_safe_defaults() {
        SubscriptionProperties defaults = new SubscriptionProperties(0, null);
        assertThat(defaults.trialDays()).isEqualTo(14);
        assertThat(defaults.trialLength()).isEqualTo(Duration.ofDays(14));
        assertThat(defaults.expiryCheckInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(new SubscriptionProperties(30, Duration.ofSeconds(5)).trialLength()).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void current_shows_the_paid_plan_with_its_next_billing_date_and_the_scheduled_change() {
        Subscription paid = Subscription.startTrial(organization, NOW, Duration.ofDays(14));
        paid.activate(PlanCode.PLUS, BillingPeriod.MONTHLY, NOW.plus(Duration.ofDays(2)), PaymentProvider.STRIPE,
                "cus_1", "sub_1");
        paid.scheduleBillingPeriodChange(BillingPeriod.ANNUAL, NOW.plus(Duration.ofDays(2)));
        subscriptions.save(paid);
        clock.advance(Duration.ofDays(3));

        var view = TenantContext.callAs(admin(), queries::current);

        assertThat(view.plan()).isEqualTo(plus);
        assertThat(view.status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(view.billingPeriod()).isEqualTo(BillingPeriod.MONTHLY);
        assertThat(view.currentPeriodStart()).isEqualTo(NOW.plus(Duration.ofDays(2)));
        assertThat(view.nextBillingAt()).isEqualTo(view.validUntil());
        assertThat(view.pendingBillingPeriod()).isEqualTo(BillingPeriod.ANNUAL);
        assertThat(view.pendingChangeAt()).isEqualTo(view.validUntil());
        assertThat(view.paymentProvider()).isEqualTo(PaymentProvider.STRIPE);

        clock.advance(Duration.ofDays(40));
        var expired = TenantContext.callAs(admin(), queries::current);
        assertThat(expired.readOnly()).isTrue();
        assertThat(expired.nextBillingAt()).isNull();
    }

    private TenantInfo admin() {
        return new TenantInfo(organization, UUID.randomUUID(), "ADMINISTRATOR");
    }
}
