package pe.buildshield.subscription.infrastructure.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private static TenantInfo admin(UUID organization) {
        return new TenantInfo(organization, UUID.randomUUID(), "ADMINISTRATOR");
    }
}
