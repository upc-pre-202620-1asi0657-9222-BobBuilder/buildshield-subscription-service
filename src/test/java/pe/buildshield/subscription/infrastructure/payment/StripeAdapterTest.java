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

    private static final Plan PLUS = new Plan(PlanCode.PLUS, "Plus", "Hasta 5 obras", 5, false,
            new BigDecimal("299.00"), new BigDecimal("2990.00"), "PEN");

    private HttpServer stripe;
    private final List<Recorded> requests = new ArrayList<>();
    private final Map<String, Response> responses = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper();

    record Recorded(String method, String path, Map<String, String> form, String authorization, String idempotencyKey) {
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
                requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), form(body),
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
        responses.put("GET /v1/subscriptions/sub_1", new Response(200, """
                {"id":"sub_1","schedule":null,"items":{"data":[{"price":{"id":"price_m","product":"prod_1"},
                "current_period_start":1760200000,"current_period_end":1762878400}]}}""", 0));
        responses.put("POST /v1/subscription_schedules", new Response(200, "{\"id\":\"sub_sched_1\"}", 0));
        responses.put("POST /v1/subscription_schedules/sub_sched_1", new Response(200, "{\"id\":\"sub_sched_1\"}", 0));

        adapter(null, Duration.ofSeconds(2)).scheduleBillingPeriodChange("sub_1", PLUS, BillingPeriod.MONTHLY,
                BillingPeriod.ANNUAL);

        assertThat(requests).extracting(Recorded::path).containsExactly("/v1/subscriptions/sub_1",
                "/v1/subscription_schedules", "/v1/subscription_schedules/sub_sched_1");
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
    void an_existing_schedule_is_reused_and_returning_to_the_current_period_releases_it() {
        responses.put("GET /v1/subscriptions/sub_2", new Response(200, """
                {"id":"sub_2","schedule":"sub_sched_2","current_period_start":10,"current_period_end":20,
                "items":{"data":[{"price":{"id":"price_y","product":"prod_1"}}]}}""", 0));
        responses.put("POST /v1/subscription_schedules/sub_sched_2", new Response(200, "{}", 0));
        responses.put("POST /v1/subscription_schedules/sub_sched_2/release", new Response(200, "{}", 0));
        StripeAdapter adapter = adapter(new PaymentsProperties.Prices(null, null, "price_pro_m", null),
                Duration.ofSeconds(2));
        Plan pro = new Plan(PlanCode.PRO, "Pro", "Ilimitadas", null, false, new BigDecimal("699"),
                new BigDecimal("6990"), "PEN");

        adapter.scheduleBillingPeriodChange("sub_2", pro, BillingPeriod.ANNUAL, BillingPeriod.MONTHLY);
        adapter.scheduleBillingPeriodChange("sub_2", pro, BillingPeriod.ANNUAL, BillingPeriod.ANNUAL);

        assertThat(requests).extracting(Recorded::path).containsExactly("/v1/subscriptions/sub_2",
                "/v1/subscription_schedules/sub_sched_2", "/v1/subscriptions/sub_2",
                "/v1/subscription_schedules/sub_sched_2/release");
        assertThat(requests.get(1).form()).containsEntry("phases[1][items][0][price]", "price_pro_m")
                .containsEntry("phases[0][end_date]", "20");
    }

    @Test
    void returning_to_the_current_period_without_schedule_does_nothing_else() {
        responses.put("GET /v1/subscriptions/sub_3", new Response(200, "{\"id\":\"sub_3\",\"schedule\":null}", 0));

        adapter(null, Duration.ofSeconds(2)).scheduleBillingPeriodChange("sub_3", PLUS, BillingPeriod.MONTHLY,
                BillingPeriod.MONTHLY);

        assertThat(requests).hasSize(1);
    }

    @Test
    void incomplete_stripe_answers_are_reported_as_unavailable() {
        responses.put("POST /v1/checkout/sessions", new Response(200, "{\"id\":\"cs_x\"}", 0));
        responses.put("GET /v1/subscriptions/sub_4", new Response(200, "{\"id\":\"sub_4\",\"items\":{\"data\":[]}}", 0));
        responses.put("GET /v1/subscriptions/sub_5", new Response(200, """
                {"id":"sub_5","items":{"data":[{"price":{"id":"price_m"},"current_period_end":99}]}}""", 0));
        responses.put("POST /v1/subscription_schedules", new Response(200, "{}", 0));
        StripeAdapter adapter = adapter(null, Duration.ofSeconds(2));

        assertThatThrownBy(() -> adapter.startCheckout(new PaymentGateway.CheckoutRequest(UUID.randomUUID(),
                UUID.randomUUID(), PLUS, BillingPeriod.MONTHLY))).hasMessageContaining("no devolvió la sesión");
        assertThatThrownBy(() -> adapter.scheduleBillingPeriodChange("sub_4", PLUS, BillingPeriod.MONTHLY,
                BillingPeriod.ANNUAL)).hasMessageContaining("periodo actual");
        assertThatThrownBy(() -> adapter.scheduleBillingPeriodChange("sub_5", PLUS, BillingPeriod.MONTHLY,
                BillingPeriod.ANNUAL)).hasMessageContaining("no devolvió el schedule");
        assertThatThrownBy(() -> adapter.scheduleBillingPeriodChange(null, PLUS, BillingPeriod.MONTHLY,
                BillingPeriod.ANNUAL)).hasMessageContaining("identificador de Stripe");
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
        assertThatThrownBy(() -> adapter.scheduleBillingPeriodChange("sub_1", PLUS, BillingPeriod.MONTHLY,
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
