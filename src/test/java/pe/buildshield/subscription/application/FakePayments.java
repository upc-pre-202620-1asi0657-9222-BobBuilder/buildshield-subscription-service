package pe.buildshield.subscription.application;

import pe.buildshield.subscription.domain.model.BillingPeriod;
import pe.buildshield.subscription.domain.model.PaymentProvider;
import pe.buildshield.subscription.domain.model.Plan;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pasarela y webhooks de prueba. La "firma" válida es {@code ok}; el cuerpo es la clave de un evento registrado con
 * {@link #willDeliver}. Registra las llamadas para comprobarlas. La misma clave de idempotencia da la misma sesión,
 * como en Stripe.
 */
class FakePayments implements PaymentGateway, PaymentWebhooks, ProcessedPaymentEvents {

    PaymentProvider provider = PaymentProvider.SIMULATED;
    final List<CheckoutRequest> checkoutRequests = new ArrayList<>();
    final List<String> scheduledChanges = new ArrayList<>();
    final List<String> scheduledSubscriptionIds = new ArrayList<>();
    final Map<String, PaymentEvent> events = new HashMap<>();
    final Set<String> processed = new HashSet<>();
    RuntimeException failure;

    @Override
    public PaymentProvider provider() {
        return provider;
    }

    @Override
    public CheckoutSession startCheckout(CheckoutRequest request) {
        if (failure != null) {
            throw failure;
        }
        checkoutRequests.add(request);
        String session = provider == PaymentProvider.SIMULATED
                ? new SimulatedSession(request.idempotencyKey(), request.plan().code(), request.period()).value()
                : "cs_" + request.idempotencyKey();
        return new CheckoutSession(session, "https://pago.test/" + session);
    }

    @Override
    public void scheduleBillingPeriodChange(UUID organizationId, String gatewaySubscriptionId, Plan plan,
            BillingPeriod current, BillingPeriod target) {
        if (failure != null) {
            throw failure;
        }
        scheduledChanges.add(organizationId + ":" + current + "->" + target);
        scheduledSubscriptionIds.add(gatewaySubscriptionId);
    }

    String willDeliver(PaymentEvent event) {
        String key = "body-" + UUID.randomUUID();
        events.put(key, event);
        return key;
    }

    @Override
    public PaymentEvent verify(String payload, String signatureHeader) {
        if (!"ok".equals(signatureHeader) || !events.containsKey(payload)) {
            throw new InvalidPaymentWebhookException("La firma del webhook no es válida");
        }
        return events.get(payload);
    }

    @Override
    public SignedWebhook simulateCheckoutCompleted(SimulatedSession session, UUID organizationId, Instant paidAt) {
        String key = willDeliver(PaymentEvent.checkoutCompleted("evt_sim_" + UUID.randomUUID(),
                "checkout.session.completed", session.value(), organizationId, session.plan(), session.period(),
                session.paymentKey(), "in_sim_" + session.paymentKey(), "sub_sim_" + session.paymentKey()));
        return new SignedWebhook(key, "ok");
    }

    @Override
    public boolean markProcessed(String eventId) {
        return processed.add(eventId);
    }
}
