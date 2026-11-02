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
 * Adaptador de Stripe contra un Stripe simulado (servidor HTTP del JDK): formulario de Checkout, clave de
 * idempotencia, autenticación, schedule del cambio de modalidad, tiempo máximo y circuit breaker.
 */
class StripeAdapterTest {

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
            synchronized (requests) {
                requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        exchange.getRequestURI().getQuery(), form(body),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("Idempotency-Key")));
            }
            Response response = responses.getOrDefault(key, new Response(404, "{\"error\":{}}", 0));
            if (response.delayMillis() > 0) {
                try {
                    Thread.sleep(response.delayMillis());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(response.status(), bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            } catch (IOException ignored) {
                // El cliente cortó por tiempo máximo.
            }
        });
        stripe.setExecutor(Executors.newCachedThreadPool());
        stripe.start();
    }

    @AfterEach
    void stopFakeStripe() {
        stripe.stop(0);
    }

    @Test
    void checkout_creates_a_subscription_session_with_inline_price_and_idempotency_key() {
        responses.put("POST /v1/checkout/sessions",
                new Response(200, "{\"id\":\"cs_test_1\",\"url\":\"https://checkout.stripe.com/c/pay/cs_test_1\"}", 0));
        UUID key = UUID.randomUUID();
        UUID organization = UUID.randomUUID();

        PaymentGateway.CheckoutSession session = adapter(null, Duration.ofSeconds(2))
                .startCheckout(new PaymentGateway.CheckoutRequest(key, organization, PLUS, BillingPeriod.ANNUAL));

        assertThat(session).isEqualTo(new PaymentGateway.CheckoutSession("cs_test_1",
                "https://checkout.stripe.com/c/pay/cs_test_1"));
        Recorded request = requests.get(0);
        assertThat(request.authorization()).isEqualTo("Bearer sk_test_fake");
        assertThat(request.idempotencyKey()).isEqualTo("checkout-" + key);
        assertThat(request.form()).containsEntry("mode", "subscription")
                .containsEntry("metadata[paymentKey]", key.toString())
                .containsEntry("subscription_data[metadata][organizationId]", organization.toString())
                .containsEntry("client_reference_id", organization.toString())
                .containsEntry("metadata[plan]", "PLUS")
                .containsEntry("metadata[billingPeriod]", "ANNUAL")
                .containsEntry("line_items[0][price_data][currency]", "pen")
                .containsEntry("line_items[0][price_data][unit_amount]", "299000")
                .containsEntry("line_items[0][price_data][recurring][interval]", "year")
                .containsEntry("line_items[0][price_data][product_data][name]", "BuildShield Plus (anual)")
                .containsEntry("success_url", "http://web/ok")
                .doesNotContainKey("line_items[0][price]");
    }

    @Test
    void checkout_uses_the_configured_stripe_price_when_there_is_one() {
        responses.put("POST /v1/checkout/sessions", new Response(200, "{\"id\":\"cs_2\",\"url\":\"https://s/cs_2\"}", 0));
        PaymentsProperties.Prices prices = new PaymentsProperties.Prices("price_plus_m", null, null, null);

        adapter(prices, Duration.ofSeconds(2)).startCheckout(new PaymentGateway.CheckoutRequest(UUID.randomUUID(),
                UUID.randomUUID(), PLUS, BillingPeriod.MONTHLY));

        assertThat(requests.get(0).form()).containsEntry("line_items[0][price]", "price_plus_m")
                .doesNotContainKey("line_items[0][price_data][unit_amount]");
    }

    @Test
    void the_billing_period_change_is_a_two_phase_schedule_from_the_next_billing_date() {
        responses.put(SEARCH, new Response(200, """
                {"data":[{"id":"sub_1","schedule":null,"items":{"data":[{"price":{"id":"price_m","product":"prod_1"},
                "current_period_start":1760200000,"current_period_end":1762878400}]}}]}""", 0));
        responses.put("POST /v1/subscription_schedules", new Response(200, "{\"id\":\"sub_sched_1\"}", 0));
        responses.put("POST /v1/subscription_schedules/sub_sched_1", new Response(200, "{\"id\":\"sub_sched_1\"}", 0));

        adapter(null, Duration.ofSeconds(2)).scheduleBillingPeriodChange(ORGANIZATION, null, PLUS, BillingPeriod.MONTHLY,
                BillingPeriod.ANNUAL);

        assertThat(requests).extracting(Recorded::path).containsExactly("/v1/subscriptions/search",
                "/v1/subscription_schedules", "/v1/subscription_schedules/sub_sched_1");
        assertThat(requests.get(0).query())
                .isEqualTo("query=metadata['organizationId']:'" + ORGANIZATION + "' AND -status:'canceled'");
        assertThat(requests.get(1).form()).containsEntry("from_subscription", "sub_1");
        assertThat(requests.get(2).form()).containsEntry("phases[0][items][0][price]", "price_m")
                .containsEntry("phases[0][start_date]", "1760200000")
                .containsEntry("phases[0][end_date]", "1762878400")
                .containsEntry("phases[1][items][0][price_data][product]", "prod_1")
                .containsEntry("phases[1][items][0][price_data][unit_amount]", "299000")
                .containsEntry("phases[1][items][0][price_data][recurring][interval]", "year")
                .containsEntry("proration_behavior", "none")
                .containsEntry("end_behavior", "release");
        assertThat(requests.get(2).idempotencyKey()).isEqualTo("change-sub_1-ANNUAL-1762878400");
    }

    @Test
    void with_a_stored_id_the_subscription_is_retrieved_directly_and_the_search_is_never_called() {
        responses.put("GET /v1/subscriptions/sub_stored", new Response(200, """
                {"id":"sub_stored","status":"active","schedule":null,"items":{"data":[{"price":{"id":"price_m",
                "product":"prod_1"},"current_period_start":1760200000,"current_period_end":1762878400}]}}""", 0));
        responses.put("POST /v1/subscription_schedules", new Response(200, "{\"id\":\"sub_sched_9\"}", 0));
        responses.put("POST /v1/subscription_schedules/sub_sched_9", new Response(200, "{\"id\":\"sub_sched_9\"}", 0));

        adapter(null, Duration.ofSeconds(2)).scheduleBillingPeriodChange(ORGANIZATION, "sub_stored", PLUS,
                BillingPeriod.MONTHLY, BillingPeriod.ANNUAL);

        assertThat(requests).extracting(Recorded::path).containsExactly("/v1/subscriptions/sub_stored",
                "/v1/subscription_schedules", "/v1/subscription_schedules/sub_sched_9");
        assertThat(requests.get(1).form()).containsEntry("from_subscription", "sub_stored");
        assertThat(requests.get(2).idempotencyKey()).isEqualTo("change-sub_stored-ANNUAL-1762878400");
    }

    @Test
    void with_a_stored_id_returning_to_the_current_period_releases_the_schedule() {
        responses.put("GET /v1/subscriptions/sub_stored", new Response(200,
                "{\"id\":\"sub_stored\",\"status\":\"active\",\"schedule\":\"sub_sched_3\"}", 0));
        responses.put("POST /v1/subscription_schedules/sub_sched_3/release", new Response(200, "{}", 0));

        adapter(null, Duration.ofSeconds(2)).scheduleBillingPeriodChange(ORGANIZATION, "sub_stored", PLUS,
                BillingPeriod.MONTHLY, BillingPeriod.MONTHLY);

        assertThat(requests).extracting(Recorded::path).containsExactly("/v1/subscriptions/sub_stored",
                "/v1/subscription_schedules/sub_sched_3/release");
    }

    @Test
    void a_stored_id_that_is_canceled_or_unknown_is_unavailable_and_does_not_fall_back_to_the_search() {
        responses.put(SEARCH, new Response(200, "{\"data\":[{\"id\":\"sub_other\",\"schedule\":null}]}", 0));
        responses.put("GET /v1/subscriptions/sub_canceled", new Response(200,
                "{\"id\":\"sub_canceled\",\"status\":\"canceled\"}", 0));
        responses.put("GET /v1/subscriptions/sub_unknown", new Response(404, "{\"error\":{}}", 0));
        StripeAdapter adapter = adapter(null, Duration.ofSeconds(2));

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
        StripeAdapter adapter = adapter(new PaymentsProperties.Prices(null, null, "price_pro_m", null),
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
        StripeAdapter adapter = adapter(null, Duration.ofSeconds(2));

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
        CircuitBreaker breaker = StripeAdapter.defaultCircuitBreaker();
        StripeAdapter adapter = new StripeAdapter(properties(null, Duration.ofSeconds(2)), json, breaker);

        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> adapter.startCheckout(request()))
                    .isInstanceOf(PaymentGateway.UnavailableException.class).hasMessageContaining("rechazó");
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void timeouts_and_server_errors_open_the_circuit_and_then_fail_fast() {
        responses.put("POST /v1/checkout/sessions", new Response(200, "{}", 1_500));
        CircuitBreaker breaker = StripeAdapter.defaultCircuitBreaker();
        StripeAdapter adapter = new StripeAdapter(properties(null, Duration.ofMillis(300)), json, breaker);

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
        StripeAdapter adapter = new StripeAdapter(new PaymentsProperties(false, null, null, null,
                new PaymentsProperties.Stripe("", null, "http://127.0.0.1:" + stripe.getAddress().getPort(), null,
                        null, null)), json, StripeAdapter.defaultCircuitBreaker());

        assertThat(adapter.provider()).isEqualTo(PaymentProvider.STRIPE);
        assertThatThrownBy(() -> adapter.startCheckout(request())).hasMessageContaining("STRIPE_SECRET_KEY");
        assertThatThrownBy(() -> adapter.scheduleBillingPeriodChange(ORGANIZATION, null, PLUS, BillingPeriod.MONTHLY,
                BillingPeriod.ANNUAL)).hasMessageContaining("STRIPE_SECRET_KEY");
        assertThat(requests).isEmpty();
        assertThat(StripeAdapter.cents(new BigDecimal("249.165"))).isEqualTo("24917");
    }

    private static PaymentGateway.CheckoutRequest request() {
        return new PaymentGateway.CheckoutRequest(UUID.randomUUID(), UUID.randomUUID(), PLUS, BillingPeriod.MONTHLY);
    }

    private StripeAdapter adapter(PaymentsProperties.Prices prices, Duration timeout) {
        PaymentsProperties base = properties(prices, timeout);
        return new StripeAdapter(base, json, StripeAdapter.defaultCircuitBreaker());
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
