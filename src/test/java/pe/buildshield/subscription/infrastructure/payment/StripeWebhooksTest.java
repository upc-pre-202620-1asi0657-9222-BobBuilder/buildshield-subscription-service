package pe.buildshield.subscription.infrastructure.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import pe.buildshield.subscription.application.InvalidPaymentWebhookException;
import pe.buildshield.subscription.application.PaymentEvent;
import pe.buildshield.subscription.application.SimulatedSession;
import pe.buildshield.subscription.domain.model.BillingPeriod;
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
        UUID key = UUID.randomUUID();
        String payload = """
                {"id":"evt_1","type":"checkout.session.completed","data":{"object":{"id":"cs_1",
                "payment_status":"paid","client_reference_id":"%s","customer":"cus_1",
                "invoice":{"id":"in_1","object":"invoice"},"subscription":"sub_1",
                "metadata":{"plan":"PLUS","billingPeriod":"ANNUAL","paymentKey":"%s"}}}}""".formatted(organization, key);

        PaymentEvent event = webhooks.verify(payload, StripeSignature.header(payload, SECRET, NOW));

        assertThat(event).isEqualTo(PaymentEvent.checkoutCompleted("evt_1", "checkout.session.completed", "cs_1",
                organization, PlanCode.PLUS, BillingPeriod.ANNUAL, key, "in_1", "sub_1"));
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

        String badMetadata = """
                {"id":"evt_4b","type":"checkout.session.completed","data":{"object":{"id":"cs_5",
                "payment_status":"paid","metadata":{"plan":"GOLD","billingPeriod":"WEEKLY","paymentKey":"x"}}}}""";
        assertThat(verify(badMetadata).plan()).isNull();
        assertThat(verify(badMetadata).billingPeriod()).isNull();
        assertThat(verify(badMetadata).paymentKey()).isNull();
    }

    @Test
    void only_renewal_invoices_start_a_new_period() {
        String renewal = """
                {"id":"evt_5","type":"invoice.paid","data":{"object":{"id":"in_5","billing_reason":"subscription_cycle",
                "amount_paid":29900,"subscription":"sub_1",
                "subscription_details":{"metadata":{"organizationId":"%s"}}}}}""".formatted(organization);
        assertThat(verify(renewal)).isEqualTo(PaymentEvent.renewalPaid("evt_5", "invoice.paid", organization, "in_5",
                new java.math.BigDecimal("299.00"), "sub_1"));

        String newApi = """
                {"id":"evt_6","type":"invoice.paid","data":{"object":{"billing_reason":"subscription_cycle",
                "parent":{"subscription_details":{"subscription":"sub_2",
                "metadata":{"organizationId":"%s"}}}}}}""".formatted(organization);
        assertThat(verify(newApi).organizationId()).isEqualTo(organization);
        assertThat(verify(newApi).amount()).isNull();
        assertThat(verify(newApi).gatewaySubscriptionId()).isEqualTo("sub_2");

        String first = """
                {"id":"evt_7","type":"invoice.paid","data":{"object":{"billing_reason":"subscription_create",
                "subscription":"sub_1"}}}""";
        assertThat(verify(first).type()).isEqualTo(PaymentEvent.Type.IGNORED);

        String other = """
                {"id":"evt_8","type":"customer.created","data":{"object":{"id":"cus_1"}}}""";
        assertThat(verify(other)).isEqualTo(PaymentEvent.ignored("evt_8", "customer.created"));
    }

    @Test
    void the_stripe_subscription_id_may_be_expanded_or_missing() {
        String expandedSession = """
                {"id":"evt_s1","type":"checkout.session.completed","data":{"object":{"id":"cs_s1",
                "payment_status":"paid","client_reference_id":"%s",
                "subscription":{"id":"sub_exp","object":"subscription"}}}}""".formatted(organization);
        assertThat(verify(expandedSession).gatewaySubscriptionId()).isEqualTo("sub_exp");

        String sessionWithout = """
                {"id":"evt_s2","type":"checkout.session.completed","data":{"object":{"id":"cs_s2",
                "payment_status":"paid","client_reference_id":"%s","subscription":null}}}""".formatted(organization);
        assertThat(verify(sessionWithout).type()).isEqualTo(PaymentEvent.Type.CHECKOUT_COMPLETED);
        assertThat(verify(sessionWithout).gatewaySubscriptionId()).isNull();

        String expandedInvoice = """
                {"id":"evt_s3","type":"invoice.paid","data":{"object":{"id":"in_s3",
                "billing_reason":"subscription_cycle","parent":{"subscription_details":{
                "subscription":{"id":"sub_exp_2"},"metadata":{"organizationId":"%s"}}}}}}""".formatted(organization);
        assertThat(verify(expandedInvoice).gatewaySubscriptionId()).isEqualTo("sub_exp_2");

        String invoiceWithout = """
                {"id":"evt_s4","type":"invoice.paid","data":{"object":{"id":"in_s4",
                "billing_reason":"subscription_cycle",
                "subscription_details":{"metadata":{"organizationId":"%s"}}}}}""".formatted(organization);
        assertThat(verify(invoiceWithout).type()).isEqualTo(PaymentEvent.Type.RENEWAL_PAID);
        assertThat(verify(invoiceWithout).gatewaySubscriptionId()).isNull();
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
        SimulatedSession session = new SimulatedSession(key, PlanCode.PLUS, BillingPeriod.ANNUAL);

        var webhook = simulated.simulateCheckoutCompleted(session, organization, NOW);
        PaymentEvent event = simulated.verify(webhook.payload(), webhook.signatureHeader());

        assertThat(event.type()).isEqualTo(PaymentEvent.Type.CHECKOUT_COMPLETED);
        assertThat(event.eventId()).startsWith("evt_sim_");
        assertThat(event.sessionId()).isEqualTo(session.value());
        assertThat(event.organizationId()).isEqualTo(organization);
        assertThat(event.plan()).isEqualTo(PlanCode.PLUS);
        assertThat(event.billingPeriod()).isEqualTo(BillingPeriod.ANNUAL);
        assertThat(event.paymentKey()).isEqualTo(key);
        assertThat(event.chargeId()).isEqualTo("in_sim_" + key.toString().replace("-", ""));
        assertThat(event.gatewaySubscriptionId()).isEqualTo("sub_sim_" + key.toString().replace("-", ""));
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
