package pe.buildshield.subscription.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pe.buildshield.subscription.application.SubscriptionCommandService.WebhookResult;
import pe.buildshield.subscription.domain.model.BillingPeriod;
import pe.buildshield.subscription.domain.model.CheckoutStatus;
import pe.buildshield.subscription.domain.model.PaymentProvider;
import pe.buildshield.subscription.domain.model.Plan;
import pe.buildshield.subscription.domain.model.PlanCode;
import pe.buildshield.subscription.domain.model.PlanRepository;
import pe.buildshield.subscription.domain.model.Subscription;
import pe.buildshield.subscription.domain.model.Subscription.BillingPeriodChange;
import pe.buildshield.subscription.domain.model.SubscriptionStatus;
import pe.buildshield.subscription.shared.error.ConflictException;
import pe.buildshield.subscription.shared.error.ResourceNotFoundException;
import pe.buildshield.subscription.shared.error.ValidationException;
import pe.buildshield.subscription.shared.messaging.OutboxWriter;
import pe.buildshield.subscription.shared.tenant.TenantContext;
import pe.buildshield.subscription.shared.tenant.TenantInfo;
import pe.buildshield.subscription.support.MutableClock;
import pe.buildshield.subscription.support.TestTransactions;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SubscriptionCommandServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-08T15:00:00Z");

    static final Plan FREE = new Plan(PlanCode.FREE, "Free", "Prueba", null, true, BigDecimal.ZERO, BigDecimal.ZERO,
            "PEN");
    static final Plan PLUS = new Plan(PlanCode.PLUS, "Plus", "Hasta 5 obras", 5, false, new BigDecimal("299.00"),
            new BigDecimal("2990.00"), "PEN");
    static final Plan PRO = new Plan(PlanCode.PRO, "Pro", "Ilimitadas", null, false, new BigDecimal("699.00"),
            new BigDecimal("6990.00"), "PEN");

    static final PlanRepository PLANS = new PlanRepository() {
        @Override
        public List<Plan> findAll() {
            return List.of(FREE, PLUS, PRO);
        }

        @Override
        public Optional<Plan> findByCode(PlanCode code) {
            return findAll().stream().filter(plan -> plan.code() == code).findFirst();
        }
    };

    private final InMemorySubscriptions subscriptions = new InMemorySubscriptions();
    private final InMemoryCheckouts checkouts = new InMemoryCheckouts();
    private final FakePayments payments = new FakePayments();
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final MutableClock clock = new MutableClock(NOW);
    private final TestTransactions transactions = new TestTransactions();
    private final SubscriptionCommandService commands = new SubscriptionCommandService(subscriptions, checkouts, PLANS,
            payments, payments, payments, outbox, new SubscriptionProperties(14, Duration.ofMinutes(1)), transactions,
            clock);
    private final UUID organization = UUID.randomUUID();

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void organization_registered_starts_a_fourteen_day_free_trial_and_announces_it() {
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
                        trial.validUntil(), null, trial.startedAt(), null, trial.startedAt(), null, null, null, null,
                        "TRIAL_STARTED")));
    }

    @Test
    void a_repeated_registration_does_not_start_a_second_trial() {
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
                eq(subscriptions.byOrganization.get(early).id()), argThat(payload ->
                        ((SubscriptionEvents.SubscriptionChanged) payload).reason().equals("EXPIRED")));
        verify(outbox, never()).append(eq(late), any(), eq("SubscriptionExpired"), any(), any(), any());
        assertThat(TenantContext.current()).isEmpty();

        assertThat(commands.expireDue()).isZero();
    }

    @Test
    void checkout_opens_a_payment_session_with_an_idempotency_key_and_reuses_a_recent_open_one() {
        trial();

        var first = asAdmin(() -> commands.startCheckout(PlanCode.PLUS, BillingPeriod.ANNUAL));

        assertThat(first.reused()).isFalse();
        assertThat(first.plan()).isEqualTo(PLUS);
        assertThat(first.checkout().status()).isEqualTo(CheckoutStatus.OPEN);
        assertThat(first.checkout().provider()).isEqualTo(PaymentProvider.SIMULATED);
        assertThat(payments.checkoutRequests).singleElement().satisfies(request -> {
            assertThat(request.organizationId()).isEqualTo(organization);
            assertThat(request.plan()).isEqualTo(PLUS);
            assertThat(request.period()).isEqualTo(BillingPeriod.ANNUAL);
            assertThat(request.idempotencyKey()).isEqualTo(first.checkout().idempotencyKey());
        });

        clock.advance(Duration.ofMinutes(10));
        var again = asAdmin(() -> commands.startCheckout(PlanCode.PLUS, BillingPeriod.ANNUAL));
        assertThat(again.reused()).isTrue();
        assertThat(again.checkout().sessionId()).isEqualTo(first.checkout().sessionId());

        var monthly = asAdmin(() -> commands.startCheckout(PlanCode.PLUS, BillingPeriod.MONTHLY));
        assertThat(monthly.reused()).isFalse();
        clock.advance(Duration.ofMinutes(25));
        assertThat(asAdmin(() -> commands.startCheckout(PlanCode.PLUS, BillingPeriod.ANNUAL)).reused()).isFalse();
        assertThat(payments.checkoutRequests).hasSize(3);
    }

    @Test
    void checkout_rejects_the_free_plan_an_unknown_subscription_and_an_already_paid_one() {
        assertThatThrownBy(() -> asAdmin(() -> commands.startCheckout(PlanCode.FREE, BillingPeriod.MONTHLY)))
                .isInstanceOf(ValidationException.class).hasFieldOrPropertyWithValue("code", "PLAN_NOT_PAYABLE");
        assertThatThrownBy(() -> asAdmin(() -> commands.startCheckout(PlanCode.PRO, BillingPeriod.MONTHLY)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasFieldOrPropertyWithValue("code", "SUBSCRIPTION_NOT_FOUND");

        trial();
        pay(PlanCode.PRO, BillingPeriod.MONTHLY);
        assertThatThrownBy(() -> asAdmin(() -> commands.startCheckout(PlanCode.PLUS, BillingPeriod.ANNUAL)))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "SUBSCRIPTION_ALREADY_ACTIVE");
    }

    @Test
    void an_unavailable_gateway_does_not_store_any_session() {
        trial();
        payments.failure = new PaymentGateway.UnavailableException("Stripe no responde");

        assertThatThrownBy(() -> asAdmin(() -> commands.startCheckout(PlanCode.PLUS, BillingPeriod.MONTHLY)))
                .isInstanceOf(PaymentGateway.UnavailableException.class);
        assertThat(checkouts.byId).isEmpty();
    }

    @Test
    void the_simulated_payment_activates_the_paid_plan_once_and_announces_it() {
        trial();
        var started = asAdmin(() -> commands.startCheckout(PlanCode.PLUS, BillingPeriod.ANNUAL));
        clock.advance(Duration.ofMinutes(2));

        WebhookResult result = asAdmin(() -> commands.completeSimulatedPayment(started.checkout().sessionId()));

        assertThat(result).isEqualTo(WebhookResult.PROCESSED);
        Subscription active = subscriptions.byOrganization.get(organization);
        assertThat(active.status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(active.plan()).isEqualTo(PlanCode.PLUS);
        assertThat(active.billingPeriod()).isEqualTo(BillingPeriod.ANNUAL);
        assertThat(active.currentPeriodStart()).isEqualTo(clock.instant());
        assertThat(active.validUntil()).isEqualTo(Instant.parse("2027-10-08T15:02:00Z"));
        assertThat(active.paymentProvider()).isEqualTo(PaymentProvider.SIMULATED);
        assertThat(active.paymentSubscriptionId()).startsWith("sub_sim_");
        assertThat(checkouts.byId.values()).singleElement()
                .satisfies(checkout -> assertThat(checkout.status()).isEqualTo(CheckoutStatus.COMPLETED));
        verify(outbox).append(eq(organization), isNull(), eq("SubscriptionActivated"), eq("SUBSCRIPTION"),
                eq(active.id()), argThat(payload -> {
                    var changed = (SubscriptionEvents.SubscriptionChanged) payload;
                    return changed.status().equals("ACTIVE") && changed.plan().equals("PLUS")
                            && changed.maxActiveWorksites() == 5 && changed.reason().equals("PAYMENT_CONFIRMED")
                            && changed.nextBillingAt().equals(active.validUntil())
                            && changed.billingPeriod().equals("ANNUAL");
                }));

        assertThat(asAdmin(() -> commands.completeSimulatedPayment(started.checkout().sessionId())))
                .isEqualTo(WebhookResult.IGNORED);
        assertThatThrownBy(() -> asAdmin(() -> commands.completeSimulatedPayment("cs_no_existe")))
                .isInstanceOf(ResourceNotFoundException.class).hasFieldOrPropertyWithValue("code", "CHECKOUT_NOT_FOUND");
    }

    @Test
    void the_simulated_payment_is_disabled_with_stripe() {
        payments.provider = PaymentProvider.STRIPE;

        assertThatThrownBy(() -> asAdmin(() -> commands.completeSimulatedPayment("cs_x")))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "PAYMENT_SIMULATION_DISABLED");
    }

    @Test
    void a_repeated_webhook_is_applied_once_and_an_unsigned_one_is_rejected() {
        trial();
        var started = asAdmin(() -> commands.startCheckout(PlanCode.PRO, BillingPeriod.MONTHLY));
        String body = payments.willDeliver(new PaymentEvent("evt_1", "checkout.session.completed",
                PaymentEvent.Type.CHECKOUT_COMPLETED, started.checkout().sessionId(), organization, "cus_1", "sub_1"));

        assertThatThrownBy(() -> commands.receiveWebhook(body, "firma-falsa"))
                .isInstanceOf(InvalidPaymentWebhookException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_WEBHOOK_SIGNATURE");
        assertThat(commands.receiveWebhook(body, "ok")).isEqualTo(WebhookResult.PROCESSED);
        assertThat(commands.receiveWebhook(body, "ok")).isEqualTo(WebhookResult.DUPLICATE);

        Subscription active = subscriptions.byOrganization.get(organization);
        assertThat(active.plan()).isEqualTo(PlanCode.PRO);
        assertThat(active.paymentCustomerId()).isEqualTo("cus_1");
        assertThat(active.paymentSubscriptionId()).isEqualTo("sub_1");
        verify(outbox, times(1)).append(eq(organization), isNull(), eq("SubscriptionActivated"), any(), any(),
                argThat(payload -> ((SubscriptionEvents.SubscriptionChanged) payload).status().equals("ACTIVE")));
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void webhooks_without_effect_are_recorded_and_ignored() {
        String other = payments.willDeliver(new PaymentEvent("evt_2", "customer.created", PaymentEvent.Type.IGNORED,
                null, null, null, null));
        String noOrganization = payments.willDeliver(new PaymentEvent("evt_3", "checkout.session.completed",
                PaymentEvent.Type.CHECKOUT_COMPLETED, "cs_x", null, null, null));
        String unknownRenewal = payments.willDeliver(new PaymentEvent("evt_4", "invoice.paid",
                PaymentEvent.Type.RENEWAL_PAID, null, null, "cus_x", "sub_desconocida"));
        String renewalWithoutSubscription = payments.willDeliver(new PaymentEvent("evt_5", "invoice.paid",
                PaymentEvent.Type.RENEWAL_PAID, null, null, null, null));
        String unknownSession = payments.willDeliver(new PaymentEvent("evt_6", "checkout.session.completed",
                PaymentEvent.Type.CHECKOUT_COMPLETED, "cs_de_otro_lado", organization, null, null));

        assertThat(commands.receiveWebhook(other, "ok")).isEqualTo(WebhookResult.IGNORED);
        assertThat(commands.receiveWebhook(other, "ok")).isEqualTo(WebhookResult.DUPLICATE);
        assertThat(commands.receiveWebhook(noOrganization, "ok")).isEqualTo(WebhookResult.IGNORED);
        assertThat(commands.receiveWebhook(unknownRenewal, "ok")).isEqualTo(WebhookResult.IGNORED);
        assertThat(commands.receiveWebhook(renewalWithoutSubscription, "ok")).isEqualTo(WebhookResult.IGNORED);
        assertThat(commands.receiveWebhook(unknownSession, "ok")).isEqualTo(WebhookResult.IGNORED);
        assertThat(payments.processed).contains("evt_2", "evt_3", "evt_4", "evt_5", "evt_6");
        verify(outbox, never()).append(any(), any(), any(), any(), any(), any());
    }

    @Test
    void a_paid_renewal_starts_the_next_period_with_the_scheduled_billing_period() {
        trial();
        payments.provider = PaymentProvider.STRIPE;
        pay(PlanCode.PLUS, BillingPeriod.MONTHLY, "sub_stripe_1");
        Instant firstEnd = subscriptions.byOrganization.get(organization).validUntil();

        BillingPeriodChange change = asAdmin(() -> commands.changeBillingPeriod(BillingPeriod.ANNUAL));
        assertThat(change).isEqualTo(BillingPeriodChange.SCHEDULED);
        assertThat(payments.scheduledChanges).containsExactly("sub_stripe_1:MONTHLY->ANNUAL");
        assertThat(subscriptions.byOrganization.get(organization).pendingBillingPeriod()).isEqualTo(BillingPeriod.ANNUAL);
        verify(outbox).append(eq(organization), isNull(), eq("SubscriptionBillingPeriodChangeScheduled"), any(), any(),
                argThat(payload -> {
                    var changed = (SubscriptionEvents.SubscriptionChanged) payload;
                    return "ANNUAL".equals(changed.pendingBillingPeriod()) && firstEnd.equals(changed.pendingChangeAt())
                            && changed.reason().equals("CHANGE_SCHEDULED");
                }));

        clock.setTo(firstEnd.plusSeconds(3600));
        String renewal = payments.willDeliver(new PaymentEvent("evt_r1", "invoice.paid",
                PaymentEvent.Type.RENEWAL_PAID, null, null, "cus_1", "sub_stripe_1"));
        assertThat(commands.receiveWebhook(renewal, "ok")).isEqualTo(WebhookResult.PROCESSED);

        Subscription renewed = subscriptions.byOrganization.get(organization);
        assertThat(renewed.billingPeriod()).isEqualTo(BillingPeriod.ANNUAL);
        assertThat(renewed.pendingBillingPeriod()).isNull();
        assertThat(renewed.currentPeriodStart()).isEqualTo(clock.instant());
        assertThat(renewed.validUntil()).isEqualTo(BillingPeriod.ANNUAL.periodEnd(clock.instant()));
        assertThat(subscriptions.systemModeQueries).contains(true);
    }

    @Test
    void asking_for_the_current_billing_period_cancels_the_scheduled_change_or_does_nothing() {
        trial();
        payments.provider = PaymentProvider.STRIPE;
        pay(PlanCode.PRO, BillingPeriod.ANNUAL, "sub_stripe_2");

        assertThat(asAdmin(() -> commands.changeBillingPeriod(BillingPeriod.ANNUAL)))
                .isEqualTo(BillingPeriodChange.UNCHANGED);
        assertThat(payments.scheduledChanges).isEmpty();
        assertThat(asAdmin(() -> commands.changeBillingPeriod(BillingPeriod.MONTHLY)))
                .isEqualTo(BillingPeriodChange.SCHEDULED);
        assertThat(asAdmin(() -> commands.changeBillingPeriod(BillingPeriod.ANNUAL)))
                .isEqualTo(BillingPeriodChange.CANCELLED);
        assertThat(payments.scheduledChanges).containsExactly("sub_stripe_2:ANNUAL->MONTHLY",
                "sub_stripe_2:ANNUAL->ANNUAL");
        assertThat(subscriptions.byOrganization.get(organization).pendingBillingPeriod()).isNull();
    }

    @Test
    void the_change_is_not_saved_when_stripe_fails_and_needs_a_paid_subscription() {
        trial();
        assertThatThrownBy(() -> asAdmin(() -> commands.changeBillingPeriod(BillingPeriod.ANNUAL)))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "SUBSCRIPTION_NOT_ACTIVE");

        payments.provider = PaymentProvider.STRIPE;
        pay(PlanCode.PLUS, BillingPeriod.MONTHLY, "sub_stripe_3");
        payments.failure = new PaymentGateway.UnavailableException("Stripe no responde");
        assertThatThrownBy(() -> asAdmin(() -> commands.changeBillingPeriod(BillingPeriod.ANNUAL)))
                .isInstanceOf(PaymentGateway.UnavailableException.class);
        assertThat(subscriptions.byOrganization.get(organization).pendingBillingPeriod()).isNull();
    }

    @Test
    void a_simulated_subscription_renews_by_itself_and_applies_the_scheduled_change() {
        trial();
        pay(PlanCode.PLUS, BillingPeriod.MONTHLY);
        assertThat(asAdmin(() -> commands.changeBillingPeriod(BillingPeriod.ANNUAL)))
                .isEqualTo(BillingPeriodChange.SCHEDULED);
        assertThat(payments.scheduledChanges).isEmpty();
        Instant end = subscriptions.byOrganization.get(organization).validUntil();

        clock.setTo(end.plusSeconds(30));
        assertThat(commands.expireDue()).isEqualTo(1);

        Subscription renewed = subscriptions.byOrganization.get(organization);
        assertThat(renewed.status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(renewed.billingPeriod()).isEqualTo(BillingPeriod.ANNUAL);
        assertThat(renewed.currentPeriodStart()).isEqualTo(end);
        verify(outbox).append(eq(organization), isNull(), eq("SubscriptionActivated"), any(), any(),
                argThat(payload -> ((SubscriptionEvents.SubscriptionChanged) payload).reason().equals("RENEWED")));
    }

    private void trial() {
        TenantContext.runAs(system(organization), () -> commands.startTrial(NOW));
    }

    private void pay(PlanCode plan, BillingPeriod period) {
        var started = asAdmin(() -> commands.startCheckout(plan, period));
        assertThat(asAdmin(() -> commands.completeSimulatedPayment(started.checkout().sessionId())))
                .isEqualTo(WebhookResult.PROCESSED);
    }

    private void pay(PlanCode plan, BillingPeriod period, String providerSubscription) {
        var started = asAdmin(() -> commands.startCheckout(plan, period));
        String body = payments.willDeliver(new PaymentEvent("evt_" + UUID.randomUUID(), "checkout.session.completed",
                PaymentEvent.Type.CHECKOUT_COMPLETED, started.checkout().sessionId(), organization, "cus_1",
                providerSubscription));
        assertThat(commands.receiveWebhook(body, "ok")).isEqualTo(WebhookResult.PROCESSED);
    }

    private <T> T asAdmin(java.util.function.Supplier<T> action) {
        return TenantContext.callAs(new TenantInfo(organization, UUID.randomUUID(), "ADMINISTRATOR"), action);
    }

    private static TenantInfo system(UUID organization) {
        return new TenantInfo(organization, TenantContext.SYSTEM_USER_ID, "SYSTEM");
    }
}
