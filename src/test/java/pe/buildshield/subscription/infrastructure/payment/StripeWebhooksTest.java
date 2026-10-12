package pe.buildshield.subscription.infrastructure.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import pe.buildshield.subscription.application.InvalidPaymentWebhookException;
import pe.buildshield.subscription.application.PaymentEvent;
import pe.buildshield.subscription.domain.model.BillingPeriod;
import pe.buildshield.subscription.domain.model.CheckoutStatus;
import pe.buildshield.subscription.domain.model.PaymentCheckout;
import pe.buildshield.subscription.domain.model.PaymentProvider;
import pe.buildshield.subscription.domain.model.PlanCode;
import pe.buildshield.subscription.support.MutableClock;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Firma {@code Stripe-Signature} y traducción de los eventos de Stripe que importan a la suscripción. */
class StripeWebhooksTest {

    private static final String SECRET = "whsec_test_only_not_a_real_secret";
    private static final Instant NOW = Instant.parse("2026-10-11T18:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final ObjectMapper json = new ObjectMapper();
    private final StripeWebhooks webhooks = new StripeWebhooks(properties(SECRET, false), json, clock);
    private final UUID organization = UUID.randomUUID();

    @Test
    void a_signed_checkout_completed_event_activates_the_organization_session() {
        String payload = """
                {"id":"evt_1","type":"checkout.session.completed","data":{"object":{"id":"cs_1",
                "payment_status":"paid","client_reference_id":"%s","customer":"cus_1",
                "subscription":{"id":"sub_1","object":"subscription"}}}}""".formatted(organization);

        PaymentEvent event = webhooks.verify(payload, StripeSignature.header(payload, SECRET, NOW));

        assertThat(event).isEqualTo(new PaymentEvent("evt_1", "checkout.session.completed",
                PaymentEvent.Type.CHECKOUT_COMPLETED, "cs_1", organization, "cus_1", "sub_1"));
    }

    @Test
    void the_organization_also_comes_from_the_metadata_and_async_payments_wait_for_success() {
        String metadataOnly = """
                {"id":"evt_2","type":"checkout.session.async_payment_succeeded","data":{"object":{"id":"cs_2",
                "metadata":{"organizationId":"%s"}}}}""".formatted(organization);
        assertThat(verify(metadataOnly).organizationId()).isEqualTo(organization);
        assertThat(verify(metadataOnly).type()).isEqualTo(PaymentEvent.Type.CHECKOUT_COMPLETED);

        String unpaid = """
                {"id":"evt_3","type":"checkout.session.completed","data":{"object":{"id":"cs_3",
                "payment_status":"unpaid","client_reference_id":"%s"}}}""".formatted(organization);
        assertThat(verify(unpaid).type()).isEqualTo(PaymentEvent.Type.IGNORED);

        String badReference = """
                {"id":"evt_4","type":"checkout.session.completed","data":{"object":{"id":"cs_4",
                "payment_status":"no_payment_required","client_reference_id":"no-es-uuid"}}}""";
        assertThat(verify(badReference).organizationId()).isNull();
    }

    @Test
    void only_renewal_invoices_start_a_new_period() {
        String renewal = """
                {"id":"evt_5","type":"invoice.paid","data":{"object":{"billing_reason":"subscription_cycle",
                "customer":"cus_1","subscription":"sub_1"}}}""";
        assertThat(verify(renewal)).isEqualTo(new PaymentEvent("evt_5", "invoice.paid", PaymentEvent.Type.RENEWAL_PAID,
                null, null, "cus_1", "sub_1"));

        String newApi = """
                {"id":"evt_6","type":"invoice.paid","data":{"object":{"billing_reason":"subscription_cycle",
                "parent":{"subscription_details":{"subscription":"sub_2"}}}}}""";
        assertThat(verify(newApi).providerSubscriptionId()).isEqualTo("sub_2");

        String first = """
                {"id":"evt_7","type":"invoice.paid","data":{"object":{"billing_reason":"subscription_create",
                "subscription":"sub_1"}}}""";
        assertThat(verify(first).type()).isEqualTo(PaymentEvent.Type.IGNORED);

        String other = """
                {"id":"evt_8","type":"customer.created","data":{"object":{"id":"cus_1"}}}""";
        assertThat(verify(other)).isEqualTo(new PaymentEvent("evt_8", "customer.created", PaymentEvent.Type.IGNORED,
                null, null, null, null));
    }

    @Test
    void forged_old_or_unsigned_webhooks_are_rejected() {
        String payload = "{\"id\":\"evt_9\",\"type\":\"customer.created\",\"data\":{\"object\":{}}}";

        assertRejected(payload, StripeSignature.header(payload, "whsec_otro", NOW), "no es válida");
        assertRejected(payload, StripeSignature.header(payload + " ", SECRET, NOW), "no es válida");
        assertRejected(payload, StripeSignature.header(payload, SECRET, NOW.minus(Duration.ofMinutes(6))),
                "tolerancia");
        assertRejected(payload, StripeSignature.header(payload, SECRET, NOW.plus(Duration.ofMinutes(6))), "tolerancia");
        assertRejected(payload, null, "Falta la firma");
        assertRejected(payload, " ", "Falta la firma");
        assertRejected(null, "t=1,v1=x", "Falta la firma");
        assertRejected(payload, "t=abc,v1=00", "marca de tiempo");
        assertRejected(payload, "t=" + NOW.getEpochSecond(), "marca de tiempo");
        assertRejected(payload, "v0=1,basura", "marca de tiempo");

        String valid = StripeSignature.header(payload, SECRET, NOW);
        assertThat(webhooks.verify(payload, "v1=deadbeef," + valid).eventId()).isEqualTo("evt_9");
        assertThat(webhooks.verify(payload, valid).eventId()).isEqualTo("evt_9");
    }

    @Test
    void the_body_must_be_a_stripe_event() {
        assertRejected("no es json", StripeSignature.header("no es json", SECRET, NOW), "no es JSON");
        String notEvent = "{\"type\":\"x\"}";
        assertRejected(notEvent, StripeSignature.header(notEvent, SECRET, NOW), "no es un evento");
    }

    @Test
    void without_a_webhook_secret_every_webhook_is_rejected() {
        StripeWebhooks unconfigured = new StripeWebhooks(properties(null, false), json, clock);
        String payload = "{}";

        assertThatThrownBy(() -> unconfigured.verify(payload, StripeSignature.header(payload, SECRET, NOW)))
                .isInstanceOf(InvalidPaymentWebhookException.class).hasMessageContaining("STRIPE_WEBHOOK_SECRET");
    }

    @Test
    void the_simulated_payment_is_a_correctly_signed_checkout_completed_event() {
        StripeWebhooks simulated = new StripeWebhooks(properties(null, true), json, clock);
        UUID key = UUID.randomUUID();
        PaymentCheckout checkout = PaymentCheckout.restore(UUID.randomUUID(), organization, key, "cs_sim_1",
                PlanCode.PLUS, BillingPeriod.ANNUAL, PaymentProvider.SIMULATED, "http://web", CheckoutStatus.OPEN, NOW,
                null, 0L);

        var webhook = simulated.simulateCheckoutCompleted(checkout, NOW);
        PaymentEvent event = simulated.verify(webhook.payload(), webhook.signatureHeader());

        assertThat(event.type()).isEqualTo(PaymentEvent.Type.CHECKOUT_COMPLETED);
        assertThat(event.eventId()).startsWith("evt_sim_");
        assertThat(event.sessionId()).isEqualTo("cs_sim_1");
        assertThat(event.organizationId()).isEqualTo(organization);
        assertThat(event.providerSubscriptionId()).isEqualTo("sub_sim_" + key.toString().replace("-", ""));
        assertThat(webhook.payload()).contains("\"plan\":\"PLUS\"", "\"billingPeriod\":\"ANNUAL\"");
        assertThatThrownBy(() -> webhooks.verify(webhook.payload(), webhook.signatureHeader()))
                .isInstanceOf(InvalidPaymentWebhookException.class);
    }

    private PaymentEvent verify(String payload) {
        return webhooks.verify(payload, StripeSignature.header(payload, SECRET, NOW));
    }

    private void assertRejected(String payload, String header, String message) {
        assertThatThrownBy(() -> webhooks.verify(payload, header))
                .isInstanceOf(InvalidPaymentWebhookException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_WEBHOOK_SIGNATURE")
                .hasMessageContaining(message);
    }

    static PaymentsProperties properties(String webhookSecret, boolean simulated) {
        return new PaymentsProperties(simulated, null, null, null,
                new PaymentsProperties.Stripe(null, webhookSecret, null, null, null, null));
    }
}
