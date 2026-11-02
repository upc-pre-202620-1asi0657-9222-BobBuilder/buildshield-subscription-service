package pe.buildshield.subscription.infrastructure.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pe.buildshield.subscription.application.ProcessedPaymentEvents;
import pe.buildshield.subscription.domain.model.BillingPeriod;
import pe.buildshield.subscription.domain.model.Payment;
import pe.buildshield.subscription.domain.model.PaymentRepository;
import pe.buildshield.subscription.domain.model.PaymentStatus;
import pe.buildshield.subscription.domain.model.PlanCode;
import pe.buildshield.subscription.domain.model.PlanRepository;
import pe.buildshield.subscription.domain.model.Subscription;
import pe.buildshield.subscription.domain.model.SubscriptionRepository;
import pe.buildshield.subscription.shared.tenant.TenantContext;
import pe.buildshield.subscription.shared.tenant.TenantInfo;
import pe.buildshield.subscription.support.SubscriptionIntegrationTest;
import pe.buildshield.subscription.support.TestDatabase;

import java.time.Duration;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Catálogo sembrado por Flyway, aislamiento entre organizaciones y unicidad de la suscripción. */
@SubscriptionIntegrationTest
class SubscriptionPersistenceIT {

    @Autowired
    PlanRepository plans;

    @Autowired
    SubscriptionRepository subscriptions;

    @Autowired
    PaymentRepository payments;

    @Autowired
    ProcessedPaymentEvents processedPayments;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        TestDatabase.reset(jdbc);
    }

    @Test
    void the_catalog_has_free_plus_and_pro_in_order() {
        var catalog = TenantContext.callAs(admin(UUID.randomUUID()), () -> tx().execute(status -> plans.findAll()));

        assertThat(catalog).extracting(plan -> plan.code()).containsExactly(PlanCode.FREE, PlanCode.PLUS, PlanCode.PRO);
        assertThat(catalog.get(0).trialOnly()).isTrue();
        assertThat(catalog.get(1).maxActiveWorksites()).isEqualTo(5);
        assertThat(catalog.get(2).unlimited()).isTrue();
        assertThat(catalog.get(1).annualDiscountPercent()).isEqualByComparingTo("16.67");
    }

    @Test
    void each_organization_only_sees_its_own_subscription_and_has_at_most_one() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Instant now = Instant.now();
        TenantContext.runAs(admin(first), () -> tx().executeWithoutResult(status ->
                subscriptions.save(Subscription.startTrial(first, now, Duration.ofDays(14)))));

        Optional<Subscription> others = TenantContext.callAs(admin(second),
                () -> tx().execute(status -> subscriptions.findCurrent()));
        assertThat(others).isEmpty();
        Subscription mine = TenantContext.callAs(admin(first), () -> tx().execute(status ->
                subscriptions.findCurrent())).orElseThrow();
        assertThat(mine.organizationId()).isEqualTo(first);
        assertThat(mine.version()).isZero();

        assertThatThrownBy(() -> TenantContext.runAs(admin(first), () -> tx().executeWithoutResult(status ->
                subscriptions.save(Subscription.startTrial(first, now, Duration.ofDays(14))))))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        List<UUID> due = TenantContext.callAsSystem(() -> tx().execute(status ->
                subscriptions.findOrganizationsDue(now.plus(Duration.ofDays(15)))));
        assertThat(due).containsExactly(first);
    }

    @Test
    void a_stale_copy_cannot_overwrite_a_newer_version() {
        UUID organization = UUID.randomUUID();
        Instant now = Instant.now();
        Subscription saved = TenantContext.callAs(admin(organization), () -> tx().execute(status ->
                subscriptions.save(Subscription.startTrial(organization, now, Duration.ofDays(1)))));
        Subscription stale = Subscription.restore(saved.id(), organization, saved.plan(), null, saved.status(),
                saved.startedAt(), saved.trialEndsAt(), null, null, saved.version());
        stale.expireIfDue(now.plus(Duration.ofDays(2)));
        TenantContext.runAs(admin(organization), () -> tx().executeWithoutResult(status -> subscriptions.save(stale)));

        assertThatThrownBy(() -> TenantContext.runAs(admin(organization), () -> tx().executeWithoutResult(status ->
                subscriptions.save(stale)))).isInstanceOf(
                org.springframework.dao.OptimisticLockingFailureException.class);
    }

    @Test
    void the_paid_subscription_keeps_its_plan_period_end_and_scheduled_change() {
        UUID organization = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Subscription saved = TenantContext.callAs(admin(organization), () -> tx().execute(status ->
                subscriptions.save(Subscription.startTrial(organization, now, Duration.ofDays(14)))));
        assertThat(saved.startedAt()).isEqualTo(now);
        saved.activate(PlanCode.PRO, BillingPeriod.MONTHLY, now);
        saved.scheduleBillingPeriodChange(BillingPeriod.ANNUAL, now);
        TenantContext.runAs(admin(organization), () -> tx().executeWithoutResult(status -> subscriptions.save(saved)));

        Optional<Subscription> current = TenantContext.callAs(admin(organization), () -> tx().execute(status ->
                subscriptions.findCurrent()));
        Subscription reloaded = current.orElseThrow();
        assertThat(reloaded.plan()).isEqualTo(PlanCode.PRO);
        assertThat(reloaded.billingPeriod()).isEqualTo(BillingPeriod.MONTHLY);
        assertThat(reloaded.periodEnd()).isEqualTo(saved.periodEnd());
        assertThat(reloaded.startedAt()).isEqualTo(now);
        assertThat(reloaded.status().name()).isEqualTo("ACTIVE");
        assertThat(reloaded.pendingBillingPeriod()).isEqualTo(BillingPeriod.ANNUAL);
    }

    @Test
    void a_trial_is_due_at_its_exact_time_and_a_paid_plan_on_its_utc_date() {
        UUID trial = UUID.randomUUID();
        UUID paid = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-08T15:00:00Z");
        TenantContext.runAs(admin(trial), () -> tx().executeWithoutResult(status ->
                subscriptions.save(Subscription.startTrial(trial, now, Duration.ofDays(14)))));
        Subscription saved = TenantContext.callAs(admin(paid), () -> tx().execute(status ->
                subscriptions.save(Subscription.startTrial(paid, now, Duration.ofDays(14)))));
        saved.activate(PlanCode.PLUS, BillingPeriod.MONTHLY, now);
        TenantContext.runAs(admin(paid), () -> tx().executeWithoutResult(status -> subscriptions.save(saved)));
        Instant trialEnd = now.plus(Duration.ofDays(14));
        Instant paidEnd = saved.periodEnd().atStartOfDay(ZoneOffset.UTC).toInstant();

        assertThat(due(trialEnd.minusSeconds(1))).isEmpty();
        assertThat(due(trialEnd)).containsExactly(trial);
        assertThat(due(paidEnd.minusSeconds(1))).containsExactly(trial);
        assertThat(due(paidEnd)).containsExactlyInAnyOrder(trial, paid);
    }

    private List<UUID> due(Instant now) {
        return TenantContext.callAsSystem(() -> tx().execute(status -> subscriptions.findOrganizationsDue(now)));
    }

    @Test
    void payments_are_isolated_by_organization_and_complete_once() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Subscription subscription = TenantContext.callAs(admin(first), () -> tx().execute(status ->
                subscriptions.save(Subscription.startTrial(first, now, Duration.ofDays(14)))));
        Payment opened = TenantContext.callAs(admin(first), () -> tx().execute(status -> payments.save(
                Payment.open(subscription.id(), first, key, new BigDecimal("2990.00"), now))));

        assertThat(opened.id()).isNotNull();
        assertThat(opened.organizationId()).isEqualTo(first);
        Optional<Payment> fromOther = TenantContext.callAs(admin(second), () -> tx().execute(status ->
                payments.findByIdempotencyKey(key)));
        assertThat(fromOther).isEmpty();
        List<Payment> pending = TenantContext.callAs(admin(first), () -> tx().execute(status -> payments.findPending()));
        assertThat(pending).extracting(Payment::idempotencyKey).containsExactly(key);

        opened.complete("in_it_" + first);
        Payment completed = TenantContext.callAs(admin(first), () -> tx().execute(status -> payments.save(opened)));
        assertThat(completed.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(completed.gatewayChargeId()).isEqualTo("in_it_" + first);
        List<Payment> stillPending = TenantContext.callAs(admin(first), () -> tx().execute(status ->
                payments.findPending()));
        assertThat(stillPending).isEmpty();
        assertThatThrownBy(() -> TenantContext.runAs(admin(first), () -> tx().executeWithoutResult(status ->
                payments.save(Payment.open(subscription.id(), first, key, BigDecimal.ONE, now)))))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void a_gateway_event_is_recorded_only_once_in_processed_events() {
        Boolean first = TenantContext.callAsSystem(() -> tx().execute(status ->
                processedPayments.markProcessed("evt_it_1")));
        Boolean repeated = TenantContext.callAsSystem(() -> tx().execute(status ->
                processedPayments.markProcessed("evt_it_1")));
        Boolean another = TenantContext.callAsSystem(() -> tx().execute(status ->
                processedPayments.markProcessed("evt_it_2")));
        assertThat(first).isTrue();
        assertThat(repeated).isFalse();
        assertThat(another).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE consumer = 'StripeWebhooks'",
                Long.class)).isEqualTo(2);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private static TenantInfo admin(UUID organization) {
        return new TenantInfo(organization, UUID.randomUUID(), "ADMINISTRATOR");
    }
}
