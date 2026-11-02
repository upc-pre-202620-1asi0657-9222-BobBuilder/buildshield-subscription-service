package pe.buildshield.subscription.infrastructure.payment;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pe.buildshield.subscription.application.PaymentGateway;
import pe.buildshield.subscription.application.SubscriptionCommandService;
import pe.buildshield.subscription.application.SubscriptionCommandService.WebhookResult;
import pe.buildshield.subscription.domain.model.BillingPeriod;
import pe.buildshield.subscription.domain.model.PaymentProvider;
import pe.buildshield.subscription.domain.model.PlanCode;
import pe.buildshield.subscription.shared.tenant.TenantContext;
import pe.buildshield.subscription.shared.tenant.TenantInfo;
import pe.buildshield.subscription.support.MutableClock;
import pe.buildshield.subscription.support.SubscriptionIntegrationTest;
import pe.buildshield.subscription.support.TestDatabase;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El camino completo con el adaptador de Stripe, contra un Stripe simulado (servidor HTTP del JDK) y PostgreSQL: el
 * webhook firmado guarda {@code gateway_subscription_id} y US12 lo usa, o busca por metadatos si la fila no lo tiene.
 * No usa el Stripe real: los cuerpos de los eventos están escritos a mano según la forma documentada de su API.
 */
@SubscriptionIntegrationTest
class StripeSubscriptionIdIT {

    private static final String SEARCH = "GET /v1/subscriptions/search";
    private static final String WEBHOOK_SECRET = "whsec_test_only_not_a_real_secret";
    private static final Instant NOW = Instant.parse("2026-11-02T15:00:00Z");

    private static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
    private static final Map<String, String> RESPONSES = new ConcurrentHashMap<>();
    private static final HttpServer STRIPE = startFakeStripe();

    @DynamicPropertySource
    static void useTheFakeStripe(DynamicPropertyRegistry registry) {
        registry.add("buildshield.payments.simulation-enabled", () -> "false");
        registry.add("buildshield.payments.stripe.secret-key", () -> "sk_test_fake");
        registry.add("buildshield.payments.stripe.api-base-url",
                () -> "http://127.0.0.1:" + STRIPE.getAddress().getPort());
    }

    @AfterAll
    static void stopFakeStripe() {
        STRIPE.stop(0);
    }

    @Autowired
    SubscriptionCommandService commands;

    @Autowired
    PaymentGateway gateway;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    private final UUID organization = UUID.randomUUID();

    @BeforeEach
    void clean() {
        TestDatabase.reset(jdbc);
        clock.setTo(NOW);
        REQUESTS.clear();
        RESPONSES.clear();
        RESPONSES.put("POST /v1/checkout/sessions", "{\"id\":\"cs_it\",\"url\":\"https://checkout.test/cs_it\"}");
        RESPONSES.put("POST /v1/subscription_schedules", "{\"id\":\"sub_sched_it\"}");
        RESPONSES.put("POST /v1/subscription_schedules/sub_sched_it", "{\"id\":\"sub_sched_it\"}");
    }

    @Test
    void the_id_from_checkout_is_stored_and_the_billing_period_change_never_searches() {
        assertThat(gateway.provider()).isEqualTo(PaymentProvider.STRIPE);
        checkoutCompleted("evt_it_1", "\"sub_it_1\"");
        assertThat(storedId()).isEqualTo("sub_it_1");
        RESPONSES.put("GET /v1/subscriptions/sub_it_1", subscription("sub_it_1"));
        REQUESTS.clear();

        asAdmin(() -> commands.changeBillingPeriod(BillingPeriod.ANNUAL));

        assertThat(REQUESTS).containsExactly("GET /v1/subscriptions/sub_it_1", "POST /v1/subscription_schedules",
                "POST /v1/subscription_schedules/sub_sched_it");
    }

    @Test
    void a_row_without_id_falls_back_to_the_search_until_a_renewal_stores_it() {
        checkoutCompleted("evt_it_2", "null");
        assertThat(storedId()).isNull();
        RESPONSES.put(SEARCH, "{\"data\":[" + subscription("sub_it_2") + "]}");
        REQUESTS.clear();

        asAdmin(() -> commands.changeBillingPeriod(BillingPeriod.ANNUAL));

        assertThat(REQUESTS).containsExactly(SEARCH, "POST /v1/subscription_schedules",
                "POST /v1/subscription_schedules/sub_sched_it");

        clock.setTo(NOW.plusSeconds(40 * 86_400));
        String renewal = """
                {"id":"evt_it_3","type":"invoice.paid","data":{"object":{"id":"in_it_3",
                "billing_reason":"subscription_cycle","amount_paid":299000,
                "parent":{"subscription_details":{"subscription":"sub_it_2",
                "metadata":{"organizationId":"%s"}}}}}}""".formatted(organization);
        assertThat(deliver(renewal)).isEqualTo(WebhookResult.PROCESSED);
        assertThat(storedId()).isEqualTo("sub_it_2");

        RESPONSES.put("GET /v1/subscriptions/sub_it_2", subscription("sub_it_2"));
        REQUESTS.clear();
        asAdmin(() -> commands.changeBillingPeriod(BillingPeriod.MONTHLY));
        assertThat(REQUESTS).contains("GET /v1/subscriptions/sub_it_2").doesNotContain(SEARCH);
    }

    private void checkoutCompleted(String eventId, String subscriptionJson) {
        TenantContext.runAs(new TenantInfo(organization, TenantContext.SYSTEM_USER_ID, "SYSTEM"),
                () -> commands.startTrial(NOW));
        var started = asAdmin(() -> commands.startCheckout(PlanCode.PLUS, BillingPeriod.MONTHLY));
        String payload = """
                {"id":"%s","type":"checkout.session.completed","data":{"object":{"id":"cs_it",
                "payment_status":"paid","client_reference_id":"%s","invoice":"in_%s","subscription":%s,
                "metadata":{"plan":"PLUS","billingPeriod":"MONTHLY","paymentKey":"%s"}}}}""".formatted(eventId,
                organization, eventId, subscriptionJson, started.payment().idempotencyKey());
        assertThat(deliver(payload)).isEqualTo(WebhookResult.PROCESSED);
    }

    private WebhookResult deliver(String payload) {
        return commands.receiveWebhook(payload, StripeSignature.header(payload, WEBHOOK_SECRET, clock.instant()));
    }

    private String storedId() {
        return jdbc.queryForObject("SELECT gateway_subscription_id FROM subscriptions WHERE organization_id = ?",
                String.class, organization);
    }

    private static String subscription(String id) {
        return """
                {"id":"%s","status":"active","schedule":null,"items":{"data":[{"price":{"id":"price_it",
                "product":"prod_it"},"current_period_start":1760200000,"current_period_end":1762878400}]}}"""
                .formatted(id);
    }

    private <T> T asAdmin(Supplier<T> action) {
        return TenantContext.callAs(new TenantInfo(organization, UUID.randomUUID(), "ADMINISTRATOR"), action);
    }

    private static HttpServer startFakeStripe() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
                REQUESTS.add(key);
                String body = RESPONSES.get(key);
                byte[] bytes = (body == null ? "{\"error\":{}}" : body).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
