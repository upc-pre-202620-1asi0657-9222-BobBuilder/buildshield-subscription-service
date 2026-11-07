package pe.buildshield.subscription.infrastructure.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pe.buildshield.subscription.application.PaymentGateway;
import pe.buildshield.subscription.domain.model.BillingPeriod;
import pe.buildshield.subscription.domain.model.PaymentProvider;
import pe.buildshield.subscription.domain.model.Plan;
import pe.buildshield.subscription.domain.model.PlanCode;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adaptador de Stripe (StripePaymentAdapter) contra un Stripe simulado (servidor HTTP del JDK): formulario de Checkout,
 * clave de idempotencia, autenticación, schedule del cambio de modalidad, tiempo máximo y circuit breaker.
 */
class StripePaymentAdapterTest {

    private static final Plan PLUS = new Plan(PlanCode.PLUS, 5, new BigDecimal("299.00"), new BigDecimal("16.67"));
    private static final UUID ORGANIZATION = UUID.fromString("9f1c2d3e-4a5b-4c6d-8e7f-9a0b1c2d3e4f");
    private static final String SEARCH = "GET /v1/subscriptions/search";

    private HttpServer stripe;
    private final List<Recorded> requests = new ArrayList<>();
    private final Map<String, Response> responses = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper();

    record Recorded(String method, String path, String query, Map<String, String> form, String authorization,
            String idempotencyKey) {
    }

    record Response(int status, String body, long delayMillis) {
    }

    @BeforeEach
    void startFakeStripe() throws IOException {
        stripe = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stripe.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getQuery(), form(body),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Idempotency-Key")));
            Response response = responses.getOrDefault(key, new Response(200, "{}", 0));
            if (response.delayMillis > 0) {
                try {
                    Thread.sleep(response.delayMillis);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = response.body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        stripe.setExecutor(Executors.newCachedThreadPool());
        stripe.start();
    }

    @AfterEach
    void stopFakeStripe() {
        if (stripe != null) {
            stripe.stop(0);
        }
    }

    @Test
    void start_checkout_posts_the_form_with_idempotency_key_and_returns_session_url() {
        responses.put("POST /v1/checkout/sessions", new Response(200, """
                {"id":"cs_test_123","url":"https://checkout.stripe.com/c/pay/cs_test_123"}""", 0));
        StripePaymentAdapter adapter = adapter(null, Duration.ofSeconds(2));
        UUID paymentKey = UUID.randomUUID();

        PaymentGateway.CheckoutSession session = adapter.startCheckout(new PaymentGateway.CheckoutRequest(paymentKey,
                ORGANIZATION, PLUS, BillingPeriod.ANNUAL));

        assertThat(session.sessionId()).isEqualTo("cs_test_123");
        assertThat(session.redirectUrl()).isEqualTo("https://checkout.stripe.com/c/pay/cs_test_123");
        Recorded request = requests.get(0);
        assertThat(request.idempotencyKey()).isEqualTo("checkout-" + paymentKey);
        assertThat(request.authorization()).isEqualTo("Bearer sk_test_fake");
        assertThat(request.form()).containsEntry("mode", "subscription")
                .containsEntry("client_reference_id", ORGANIZATION.toString())
                .containsEntry("metadata[organizationId]", ORGANIZATION.toString())
                .containsEntry("metadata[plan]", "PLUS")
                .containsEntry("metadata[billingPeriod]", "ANNUAL")
                .containsEntry("metadata[paymentKey]", paymentKey.toString())
                .containsEntry("line_items[0][price_data][unit_amount]", "299000")
                .containsEntry("line_items[0][price_data][recurring][interval]", "year")
                .containsEntry("line_items[0][price_data][currency]", "pen")
                .containsEntry("success_url", "http://web/ok")
                .containsEntry("cancel_url", "http://web/cancel");
    }

    @Test
    void start_checkout_uses_configured_price_id_when_present() {
        responses.put("POST /v1/checkout/sessions", new Response(200, "{\"id\":\"cs_x\",\"url\":\"http://pay\"}", 0));
        StripePaymentAdapter adapter = adapter(new PaymentsProperties.Prices("price_plus_m", "price_plus_a", null, null),
                Duration.ofSeconds(2));

        adapter.startCheckout(new PaymentGateway.CheckoutRequest(UUID.randomUUID(), ORGANIZATION, PLUS,
                BillingPeriod.MONTHLY));

        assertThat(requests.get(0).form()).containsEntry("line_items[0][price]", "price_plus_m")
                .doesNotContainKey("line_items[0][price_data][unit_amount]");
    }

    @Test
    void schedule_billing_period_change_creates_a_two_phase_schedule_with_stored_subscription_id() {
        responses.put("GET /v1/subscriptions/sub_stored", new Response(200, """
                {"id":"sub_stored","schedule":null,"items":{"data":[{"price":{"id":"price_old","product":"prod_1"},
                "current_period_start":1700000000,"current_period_end":1702592000}]}}""", 0));
        responses.put("POST /v1/subscription_schedules", new Response(200, "{\"id\":\"sub_sched_1\"}", 0));
        responses.put("POST /v1/subscription_schedules/sub_sched_1", new Response(200, "{}", 0));
        StripePaymentAdapter adapter = adapter(null, Duration.ofSeconds(2));

        adapter.scheduleBillingPeriodChange(ORGANIZATION, "sub_stored", PLUS, BillingPeriod.MONTHLY,
                BillingPeriod.ANNUAL);

        assertThat(requests).extracting(Recorded::path).containsExactly("/v1/subscriptions/sub_stored",
                "/v1/subscription_schedules", "/v1/subscription_schedules/sub_sched_1");
        assertThat(requests.get(0).path()).isEqualTo("/v1/subscriptions/sub_stored");
        assertThat(requests.get(1).form()).containsEntry("from_subscription", "sub_stored");
        Recorded update = requests.get(2);
        assertThat(update.form()).containsEntry("phases[0][items][0][price]", "price_old")
                .containsEntry("phases[0][start_date]", "1700000000")
                .containsEntry("phases[0][end_date]", "1702592000")
                .containsEntry("phases[1][items][0][price_data][unit_amount]", "299000")
                .containsEntry("phases[1][items][0][price_data][recurring][interval]", "year")
                .containsEntry("phases[1][items][0][price_data][product]", "prod_1")
                .containsEntry("end_behavior", "release")
                .containsEntry("proration_behavior", "none");
    }

    @Test
    void schedule_billing_period_change_falls_back_to_search_when_the_id_is_null() {
        responses.put(SEARCH, new Response(200, """
                {"data":[{"id":"sub_found","schedule":null,"current_period_start":100,"current_period_end":200,
                "items":{"data":[{"price":{"id":"price_old","product":"prod_x"}}]}}]}""", 0));
        responses.put("POST /v1/subscription_schedules", new Response(200, "{\"id\":\"sub_sched_x\"}", 0));
        responses.put("POST /v1/subscription_schedules/sub_sched_x", new Response(200, "{}", 0));
        StripePaymentAdapter adapter = adapter(null, Duration.ofSeconds(2));

        adapter.scheduleBillingPeriodChange(ORGANIZATION, null, PLUS, BillingPeriod.MONTHLY, BillingPeriod.ANNUAL);

        assertThat(requests).extracting(Recorded::path).containsExactly("/v1/subscriptions/search",
                "/v1/subscription_schedules", "/v1/subscription_schedules/sub_sched_x");
        assertThat(requests.get(0).query())
                .isEqualTo("query=metadata['organizationId']:'" + ORGANIZATION + "' AND -status:'canceled'");
    }

    @Test
    void returning_to_current_period_releases_the_schedule_using_stored_id() {
        responses.put("GET /v1/subscriptions/sub_stored_2", new Response(200, """
                {"id":"sub_stored_2","schedule":"sub_sched_3","status":"active"}""", 0));
        responses.put("POST /v1/subscription_schedules/sub_sched_3/release", new Response(200, "{}", 0));
        StripePaymentAdapter adapter = adapter(null, Duration.ofSeconds(2));

        adapter.scheduleBillingPeriodChange(ORGANIZATION, "sub_stored_2", PLUS, BillingPeriod.MONTHLY,
                BillingPeriod.MONTHLY);

        assertThat(requests).extracting(Recorded::path).containsExactly("/v1/subscriptions/sub_stored_2",
                "/v1/subscription_schedules/sub_sched_3/release");
    }

    @Test
    void a_stored_id_that_is_canceled_or_unknown_is_unavailable_and_does_not_fall_back_to_the_search() {
        responses.put(SEARCH, new Response(200, "{\"data\":[{\"id\":\"sub_other\",\"schedule\":null}]}", 0));
        responses.put("GET /v1/subscriptions/sub_canceled", new Response(200,
                "{\"id\":\"sub_canceled\",\"status\":\"canceled\"}", 0));
        responses.put("GET /v1/subscriptions/sub_unknown", new Response(404, "{\"error\":{}}", 0));
        StripePaymentAdapter adapter = adapter(null, Duration.ofSeconds(2));

        assertThatThrownBy(() -> adapter.scheduleBillingPeriodChange(ORGANIZATION, "sub_canceled", PLUS,
                BillingPeriod.MONTHLY, BillingPeriod.ANNUAL))
                .isInstanceOf(PaymentGateway.UnavailableException.class).hasMessageContaining("suscripción vigente");
        assertThatThrownBy(() -> adapter.scheduleBillingPeriodChange(ORGANIZATION, "sub_unknown", PLUS,
                BillingPeriod.MONTHLY, BillingPeriod.ANNUAL))
                .isInstanceOf(PaymentGateway.UnavailableException.class).hasMessageContaining("rechazó");

        assertThat(requests).extracting(Recorded::path).containsExactly("/v1/subscriptions/sub_canceled",
                "/v1/subscriptions/sub_unknown");
    }

    @Test
    void an_existing_schedule_is_reused_and_returning_to_the_current_period_releases_it() {
        responses.put(SEARCH, new Response(200, """
                {"data":[{"id":"sub_2","schedule":"sub_sched_2","current_period_start":10,"current_period_end":20,
                "items":{"data":[{"price":{"id":"price_y","product":"prod_1"}}]}}]}""", 0));
        responses.put("POST /v1/subscription_schedules/sub_sched_2", new Response(200, "{}", 0));
        responses.put("POST /v1/subscription_schedules/sub_sched_2/release", new Response(200, "{}", 0));
        StripePaymentAdapter adapter = adapter(new PaymentsProperties.Prices(null, null, "price_pro_m", null),
                Duration.ofSeconds(2));
        Plan pro = new Plan(PlanCode.PRO, null, new BigDecimal("699"), new BigDecimal("16.67"));

        adapter.scheduleBillingPeriodChange(ORGANIZATION, null, pro, BillingPeriod.ANNUAL, BillingPeriod.MONTHLY);
        adapter.scheduleBillingPeriodChange(ORGANIZATION, null, pro, BillingPeriod.ANNUAL, BillingPeriod.ANNUAL);

        assertThat(requests).extracting(Recorded::path).containsExactly("/v1/subscriptions/search",
                "/v1/subscription_schedules/sub_sched_2", "/v1/subscriptions/search",
                "/v1/subscription_schedules/sub_sched_2/release");
        assertThat(requests.get(1).form()).containsEntry("phases[1][items][0][price]", "price_pro_m")
                .containsEntry("phases[0][end_date]", "20");
    }

    @Test
    void returning_to_the_current_period_without_schedule_does_nothing_else() {
        responses.put(SEARCH, new Response(200, "{\"data\":[{\"id\":\"sub_3\",\"schedule\":null}]}", 0));

        adapter(null, Duration.ofSeconds(2)).scheduleBillingPeriodChange(ORGANIZATION, null, PLUS, BillingPeriod.MONTHLY,
                BillingPeriod.MONTHLY);

        assertThat(requests).hasSize(1);
    }

    @Test
    void incomplete_stripe_answers_are_reported_as_unavailable() {
        responses.put("POST /v1/checkout/sessions", new Response(200, "{\"id\":\"cs_x\"}", 0));
        responses.put("POST /v1/subscription_schedules", new Response(200, "{}", 0));
        StripePaymentAdapter adapter = adapter(null, Duration.ofSeconds(2));

        assertThatThrownBy(() -> adapter.startCheckout(new PaymentGateway.CheckoutRequest(UUID.randomUUID(),
                UUID.randomUUID(), PLUS, BillingPeriod.MONTHLY))).hasMessageContaining("no devolvió la sesión");
        responses.put(SEARCH, new Response(200, "{\"data\":[{\"id\":\"sub_4\",\"items\":{\"data\":[]}}]}", 0));
        assertThatThrownBy(() -> adapter.scheduleBillingPeriodChange(ORGANIZATION, null, PLUS, BillingPeriod.MONTHLY,
                BillingPeriod.ANNUAL)).hasMessageContaining("periodo actual");
        responses.put(SEARCH, new Response(200, """
                {"data":[{"id":"sub_5","items":{"data":[{"price":{"id":"price_m"},"current_period_end":99}]}}]}""", 0));
        assertThatThrownBy(() -> adapter.scheduleBillingPeriodChange(ORGANIZATION, null, PLUS, BillingPeriod.MONTHLY,
                BillingPeriod.ANNUAL)).hasMessageContaining("no devolvió el schedule");
        responses.put(SEARCH, new Response(200, "{\"data\":[]}", 0));
        assertThatThrownBy(() -> adapter.scheduleBillingPeriodChange(ORGANIZATION, null, PLUS, BillingPeriod.MONTHLY,
                BillingPeriod.ANNUAL)).hasMessageContaining("suscripción vigente");
    }

    @Test
    void a_rejected_request_is_unavailable_but_does_not_open_the_circuit() {
        responses.put("POST /v1/checkout/sessions", new Response(400, "{\"error\":{\"message\":\"Invalid\"}}", 0));
        CircuitBreaker breaker = StripePaymentAdapter.defaultCircuitBreaker();
        StripePaymentAdapter adapter = new StripePaymentAdapter(properties(null, Duration.ofSeconds(2)), json, breaker);

        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> adapter.startCheckout(request()))
                    .isInstanceOf(PaymentGateway.UnavailableException.class).hasMessageContaining("rechazó");
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void timeouts_and_server_errors_open_the_circuit_and_then_fail_fast() {
        responses.put("POST /v1/checkout/sessions", new Response(200, "{}", 1_500));
        CircuitBreaker breaker = StripePaymentAdapter.defaultCircuitBreaker();
        StripePaymentAdapter adapter = new StripePaymentAdapter(properties(null, Duration.ofMillis(300)), json, breaker);

        assertThatThrownBy(() -> adapter.startCheckout(request()))
                .isInstanceOf(PaymentGateway.UnavailableException.class).hasMessageContaining("no respondió");
        responses.put("POST /v1/checkout/sessions", new Response(500, "{}", 0));
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> adapter.startCheckout(request()))
                    .isInstanceOf(PaymentGateway.UnavailableException.class);
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int before = requests.size();
        assertThatThrownBy(() -> adapter.startCheckout(request()))
                .isInstanceOf(PaymentGateway.UnavailableException.class).hasMessageContaining("fallas recientes");
        assertThat(requests).hasSize(before);
    }

    @Test
    void without_secret_key_stripe_is_not_called() {
        StripePaymentAdapter adapter = new StripePaymentAdapter(new PaymentsProperties(false, null, null, null,
                new PaymentsProperties.Stripe("", null, "http://127.0.0.1:" + stripe.getAddress().getPort(), null,
                        null, null)), json, StripePaymentAdapter.defaultCircuitBreaker());

        assertThat(adapter.provider()).isEqualTo(PaymentProvider.STRIPE);
        assertThatThrownBy(() -> adapter.startCheckout(request())).hasMessageContaining("STRIPE_SECRET_KEY");
        assertThatThrownBy(() -> adapter.scheduleBillingPeriodChange(ORGANIZATION, null, PLUS, BillingPeriod.MONTHLY,
                BillingPeriod.ANNUAL)).hasMessageContaining("STRIPE_SECRET_KEY");
        assertThat(requests).isEmpty();
        assertThat(StripePaymentAdapter.cents(new BigDecimal("249.165"))).isEqualTo("24917");
    }

    private static PaymentGateway.CheckoutRequest request() {
        return new PaymentGateway.CheckoutRequest(UUID.randomUUID(), UUID.randomUUID(), PLUS, BillingPeriod.MONTHLY);
    }

    private StripePaymentAdapter adapter(PaymentsProperties.Prices prices, Duration timeout) {
        PaymentsProperties base = properties(prices, timeout);
        return new StripePaymentAdapter(base, json, StripePaymentAdapter.defaultCircuitBreaker());
    }

    private PaymentsProperties properties(PaymentsProperties.Prices prices, Duration timeout) {
        return new PaymentsProperties(false, "http://web/ok", "http://web/cancel", null,
                new PaymentsProperties.Stripe("sk_test_fake", "whsec_x",
                        "http://127.0.0.1:" + stripe.getAddress().getPort() + "/", timeout, null, prices));
    }

    private static Map<String, String> form(String body) {
        Map<String, String> form = new LinkedHashMap<>();
        if (body.isBlank()) {
            return form;
        }
        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);
            form.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return form;
    }
}
