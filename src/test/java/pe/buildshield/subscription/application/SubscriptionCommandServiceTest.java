package pe.buildshield.subscription.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pe.buildshield.subscription.domain.model.PlanCode;
import pe.buildshield.subscription.domain.model.SubscriptionStatus;
import pe.buildshield.subscription.shared.messaging.OutboxWriter;
import pe.buildshield.subscription.shared.tenant.TenantContext;
import pe.buildshield.subscription.shared.tenant.TenantInfo;
import pe.buildshield.subscription.support.MutableClock;
import pe.buildshield.subscription.support.TestTransactions;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SubscriptionCommandServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-08T15:00:00Z");

    private final InMemorySubscriptions subscriptions = new InMemorySubscriptions();
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final MutableClock clock = new MutableClock(NOW);
    private final TestTransactions transactions = new TestTransactions();
    private final SubscriptionCommandService commands = new SubscriptionCommandService(subscriptions, outbox,
            new SubscriptionProperties(14, Duration.ofMinutes(1)), transactions, clock);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void organization_registered_starts_a_fourteen_day_free_trial_and_announces_it() {
        UUID organization = UUID.randomUUID();

        boolean started = TenantContext.callAs(system(organization),
                () -> commands.startTrial(NOW.minus(Duration.ofMinutes(5))));

        assertThat(started).isTrue();
        var trial = subscriptions.byOrganization.get(organization);
        assertThat(trial.plan()).isEqualTo(PlanCode.FREE);
        assertThat(trial.status()).isEqualTo(SubscriptionStatus.TRIAL);
        assertThat(trial.startedAt()).isEqualTo(NOW.minus(Duration.ofMinutes(5)));
        assertThat(trial.validUntil()).isEqualTo(NOW.minus(Duration.ofMinutes(5)).plus(Duration.ofDays(14)));
        verify(outbox).append(eq(organization), isNull(), eq("SubscriptionActivated"), eq("SUBSCRIPTION"),
                eq(trial.id()), eq(new SubscriptionEvents.SubscriptionChanged(organization, trial.id(), "FREE", "TRIAL",
                        trial.validUntil(), null, trial.startedAt())));
    }

    @Test
    void a_repeated_registration_does_not_start_a_second_trial() {
        UUID organization = UUID.randomUUID();
        TenantContext.runAs(system(organization), () -> commands.startTrial(NOW));

        boolean again = TenantContext.callAs(system(organization), () -> commands.startTrial(NOW.plusSeconds(30)));

        assertThat(again).isFalse();
        verify(outbox, times(1)).append(any(), any(), any(), any(), any(), any());
    }

    @Test
    void the_trial_never_starts_in_the_future_nor_without_a_date() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        TenantContext.runAs(system(first), () -> commands.startTrial(NOW.plus(Duration.ofDays(2))));
        TenantContext.runAs(system(second), () -> commands.startTrial(null));

        assertThat(subscriptions.byOrganization.get(first).startedAt()).isEqualTo(NOW);
        assertThat(subscriptions.byOrganization.get(second).startedAt()).isEqualTo(NOW);
    }

    @Test
    void expiry_check_expires_only_the_due_subscriptions_and_publishes_subscription_expired() {
        UUID early = UUID.randomUUID();
        UUID late = UUID.randomUUID();
        TenantContext.runAs(system(early), () -> commands.startTrial(NOW));
        clock.advance(Duration.ofDays(5));
        TenantContext.runAs(system(late), () -> commands.startTrial(clock.instant()));

        clock.advance(Duration.ofDays(9));
        int expired = commands.expireDue();

        assertThat(expired).isEqualTo(1);
        assertThat(subscriptions.byOrganization.get(early).status()).isEqualTo(SubscriptionStatus.EXPIRED);
        assertThat(subscriptions.byOrganization.get(early).expiredAt()).isEqualTo(clock.instant());
        assertThat(subscriptions.byOrganization.get(late).status()).isEqualTo(SubscriptionStatus.TRIAL);
        assertThat(subscriptions.systemModeQueries).containsOnly(true);
        verify(outbox).append(eq(early), isNull(), eq("SubscriptionExpired"), eq("SUBSCRIPTION"),
                eq(subscriptions.byOrganization.get(early).id()), any());
        verify(outbox, never()).append(eq(late), any(), eq("SubscriptionExpired"), any(), any(), any());
        assertThat(TenantContext.current()).isEmpty();

        assertThat(commands.expireDue()).isZero();
    }

    private static TenantInfo system(UUID organization) {
        return new TenantInfo(organization, TenantContext.SYSTEM_USER_ID, "SYSTEM");
    }
}
