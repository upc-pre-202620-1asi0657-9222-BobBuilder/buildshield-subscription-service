package pe.buildshield.subscription.infrastructure.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pe.buildshield.subscription.application.ProcessedPaymentEvents;
import pe.buildshield.subscription.domain.model.BillingPeriod;
import pe.buildshield.subscription.domain.model.CheckoutStatus;
import pe.buildshield.subscription.domain.model.PaymentCheckout;
import pe.buildshield.subscription.domain.model.PaymentCheckoutRepository;
import pe.buildshield.subscription.domain.model.PaymentProvider;
import pe.buildshield.subscription.domain.model.PlanCode;
import pe.buildshield.subscription.domain.model.PlanRepository;
import pe.buildshield.subscription.domain.model.Subscription;
import pe.buildshield.subscription.domain.model.SubscriptionRepository;
import pe.buildshield.subscription.shared.tenant.TenantContext;
import pe.buildshield.subscription.shared.tenant.TenantInfo;
import pe.buildshield.subscription.support.SubscriptionIntegrationTest;
import pe.buildshield.subscription.support.TestDatabase;

import java.time.Duration;
import java.time.Instant;
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
    PaymentCheckoutRepository checkouts;

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
                saved.startedAt(), saved.validUntil(), null, saved.version());
        stale.expireIfDue(now.plus(Duration.ofDays(2)));
        TenantContext.runAs(admin(organization), () -> tx().executeWithoutResult(status -> subscriptions.save(stale)));

        assertThatThrownBy(() -> TenantContext.runAs(admin(organization), () -> tx().executeWithoutResult(status ->
                subscriptions.save(stale)))).isInstanceOf(
                org.springframework.dao.OptimisticLockingFailureException.class);
    }

    @Test
    void the_paid_subscription_keeps_its_payment_details_and_is_found_by_the_gateway_subscription() {
        UUID organization = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Subscription saved = TenantContext.callAs(admin(organization), () -> tx().execute(status ->
                subscriptions.save(Subscription.startTrial(organization, now, Duration.ofDays(14)))));
        saved.activate(PlanCode.PRO, BillingPeriod.MONTHLY, now, PaymentProvider.STRIPE, "cus_it",
                "sub_it_" + organization);
        saved.scheduleBillingPeriodChange(BillingPeriod.ANNUAL, now);
        TenantContext.runAs(admin(organization), () -> tx().executeWithoutResult(status -> subscriptions.save(saved)));

        Optional<Subscription> current = TenantContext.callAs(admin(organization), () -> tx().execute(status ->
                subscriptions.findCurrent()));
        Subscription reloaded = current.orElseThrow();
        assertThat(reloaded.payment()).isEqualTo(saved.payment());
        assertThat(reloaded.status().name()).isEqualTo("ACTIVE");
        assertThat(reloaded.pendingBillingPeriod()).isEqualTo(BillingPeriod.ANNUAL);
        Optional<UUID> owner = TenantContext.callAsSystem(() -> tx().execute(status ->
                subscriptions.findOrganizationByPaymentSubscriptionId("sub_it_" + organization)));
        Optional<UUID> nobody = TenantContext.callAsSystem(() -> tx().execute(status ->
                subscriptions.findOrganizationByPaymentSubscriptionId("sub_otra")));
        assertThat(owner).contains(organization);
        assertThat(nobody).isEmpty();
    }

    @Test
    void checkouts_are_isolated_by_organization_and_complete_once() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Instant now = Instant.now();
        PaymentCheckout opened = TenantContext.callAs(admin(first), () -> tx().execute(status -> checkouts.save(
                PaymentCheckout.open(UUID.randomUUID(), first, "cs_it_" + first, PlanCode.PLUS, BillingPeriod.ANNUAL,
                        PaymentProvider.SIMULATED, "http://localhost:4200/suscripcion/pago-simulado", now))));

        assertThat(opened.id()).isNotNull();
        Optional<PaymentCheckout> fromOther = TenantContext.callAs(admin(second), () -> tx().execute(status ->
                checkouts.findBySessionId("cs_it_" + first)));
        assertThat(fromOther).isEmpty();
        List<PaymentCheckout> open = TenantContext.callAs(admin(first), () -> tx().execute(status ->
                checkouts.findOpen()));
        assertThat(open).extracting(PaymentCheckout::sessionId).containsExactly("cs_it_" + first);

        opened.complete(now.plusSeconds(5));
        PaymentCheckout completed = TenantContext.callAs(admin(first), () -> tx().execute(status ->
                checkouts.save(opened)));
        assertThat(completed.status()).isEqualTo(CheckoutStatus.COMPLETED);
        assertThat(completed.version()).isEqualTo(1L);
        List<PaymentCheckout> stillOpen = TenantContext.callAs(admin(first), () -> tx().execute(status ->
                checkouts.findOpen()));
        assertThat(stillOpen).isEmpty();
        assertThatThrownBy(() -> TenantContext.runAs(admin(first), () -> tx().executeWithoutResult(status ->
                checkouts.save(opened)))).isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
    }

    @Test
    void a_gateway_event_is_recorded_only_once() {
        Boolean first = TenantContext.callAsSystem(() -> tx().execute(status ->
                processedPayments.markProcessed("evt_it_1", "invoice.paid")));
        Boolean repeated = TenantContext.callAsSystem(() -> tx().execute(status ->
                processedPayments.markProcessed("evt_it_1", "invoice.paid")));
        Boolean withoutType = TenantContext.callAsSystem(() -> tx().execute(status ->
                processedPayments.markProcessed("evt_it_2", null)));
        assertThat(first).isTrue();
        assertThat(repeated).isFalse();
        assertThat(withoutType).isTrue();
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private static TenantInfo admin(UUID organization) {
        return new TenantInfo(organization, UUID.randomUUID(), "ADMINISTRATOR");
    }
}
