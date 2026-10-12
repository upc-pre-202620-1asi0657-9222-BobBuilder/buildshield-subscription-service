package pe.buildshield.subscription.infrastructure.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import pe.buildshield.subscription.application.PaymentGateway;
import pe.buildshield.subscription.domain.model.BillingPeriod;
import pe.buildshield.subscription.domain.model.PaymentProvider;
import pe.buildshield.subscription.domain.model.Plan;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Adaptador de Stripe para {@link PaymentGateway}, con la API HTTP de Stripe (formularios
 * {@code application/x-www-form-urlencoded} y autenticación con la clave secreta).
 *
 * <ul>
 *   <li>Checkout: {@code POST /v1/checkout/sessions} en modo {@code subscription}, con {@code Idempotency-Key}, la
 *       organización en {@code client_reference_id} y en los metadatos, y el precio configurado
 *       ({@code STRIPE_PRICE_*}) o en línea ({@code price_data}) desde el catálogo de planes.</li>
 *   <li>Cambio de modalidad (US12): un {@code subscription_schedule} de dos fases, la actual hasta el fin del periodo y
 *       la nueva modalidad desde el siguiente cobro, sin prorrateo. Volver a la modalidad actual libera el schedule.</li>
 * </ul>
 *
 * Cada llamada tiene tiempo máximo de conexión y de respuesta ({@code STRIPE_TIMEOUT}) y pasa por un circuit breaker
 * (Resilience4j): tras fallas seguidas responde de inmediato "no disponible" (503) en vez de esperar a Stripe. Los
 * rechazos 4xx de Stripe (datos o configuración) no abren el circuito. Nunca se envían ni se reciben datos de tarjeta.
 */
class StripeAdapter implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(StripeAdapter.class);

    private final PaymentsProperties properties;
    private final ObjectMapper json;
    private final CircuitBreaker circuitBreaker;
    private final RestClient http;

    StripeAdapter(PaymentsProperties properties, ObjectMapper json, CircuitBreaker circuitBreaker) {
        this.properties = properties;
        this.json = json;
        this.circuitBreaker = circuitBreaker;
        Duration timeout = properties.stripe().timeout();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);
        this.http = RestClient.builder()
                .baseUrl(properties.stripe().apiBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + nullToEmpty(properties.stripe().secretKey()))
                .build();
    }

    /** Circuit breaker de Stripe: se abre con 50 % de fallas en las últimas 10 llamadas y prueba de nuevo a los 30 s. */
    static CircuitBreaker defaultCircuitBreaker() {
        return CircuitBreaker.of("stripe", CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(2)
                .ignoreExceptions(StripeRejectedException.class)
                .build());
    }

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.STRIPE;
    }

    @Override
    public CheckoutSession startCheckout(CheckoutRequest request) {
        requireConfigured();
        Plan plan = request.plan();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("mode", "subscription");
        form.add("success_url", properties.successUrl());
        form.add("cancel_url", properties.cancelUrl());
        form.add("client_reference_id", request.organizationId().toString());
        form.add("metadata[organizationId]", request.organizationId().toString());
        form.add("metadata[plan]", plan.code().name());
        form.add("metadata[billingPeriod]", request.period().name());
        form.add("subscription_data[metadata][organizationId]", request.organizationId().toString());
        form.add("subscription_data[metadata][plan]", plan.code().name());
        form.add("line_items[0][quantity]", "1");
        addPrice(form, "line_items[0]", plan, request.period(), true, null);
        JsonNode session = post("/v1/checkout/sessions", form, "checkout-" + request.idempotencyKey());
        String sessionId = session.path("id").asText(null);
        String url = session.path("url").asText(null);
        if (sessionId == null || url == null) {
            throw new UnavailableException("Stripe no devolvió la sesión de pago");
        }
        return new CheckoutSession(sessionId, url);
    }

    @Override
    public void scheduleBillingPeriodChange(String providerSubscriptionId, Plan plan, BillingPeriod current,
            BillingPeriod target) {
        requireConfigured();
        if (providerSubscriptionId == null) {
            throw new UnavailableException("La suscripción no tiene su identificador de Stripe");
        }
        JsonNode subscription = get("/v1/subscriptions/" + providerSubscriptionId);
        String schedule = subscription.path("schedule").isTextual() ? subscription.path("schedule").asText() : null;
        if (target == current) {
            if (schedule != null) {
                post("/v1/subscription_schedules/" + schedule + "/release", new LinkedMultiValueMap<>(),
                        "release-" + schedule + "-" + providerSubscriptionId);
            }
            return;
        }
        JsonNode item = subscription.path("items").path("data").path(0);
        String currentPrice = item.path("price").path("id").asText(null);
        String product = item.path("price").path("product").asText(null);
        long periodStart = periodBound(subscription, item, "current_period_start");
        long periodEnd = periodBound(subscription, item, "current_period_end");
        if (currentPrice == null || periodEnd <= 0) {
            throw new UnavailableException("Stripe no devolvió el periodo actual de la suscripción");
        }
        if (schedule == null) {
            MultiValueMap<String, String> create = new LinkedMultiValueMap<>();
            create.add("from_subscription", providerSubscriptionId);
            schedule = post("/v1/subscription_schedules", create, "schedule-" + providerSubscriptionId)
                    .path("id").asText(null);
            if (schedule == null) {
                throw new UnavailableException("Stripe no devolvió el schedule de la suscripción");
            }
        }
        MultiValueMap<String, String> phases = new LinkedMultiValueMap<>();
        phases.add("end_behavior", "release");
        phases.add("proration_behavior", "none");
        phases.add("phases[0][items][0][price]", currentPrice);
        phases.add("phases[0][items][0][quantity]", "1");
        phases.add("phases[0][start_date]", Long.toString(periodStart));
        phases.add("phases[0][end_date]", Long.toString(periodEnd));
        addPrice(phases, "phases[1][items][0]", plan, target, false, product);
        phases.add("phases[1][items][0][quantity]", "1");
        phases.add("phases[1][iterations]", "1");
        post("/v1/subscription_schedules/" + schedule, phases,
                "change-" + providerSubscriptionId + "-" + target + "-" + periodEnd);
        log.info("Stripe: cambio a {} programado para {} desde {}", target, providerSubscriptionId, periodEnd);
    }

    /** Precio del ítem: el id configurado o el precio en línea del catálogo (céntimos, recurrente mensual o anual). */
    private void addPrice(MultiValueMap<String, String> form, String prefix, Plan plan, BillingPeriod period,
            boolean withProductData, String product) {
        String priceId = properties.stripe().prices().priceId(plan.code(), period);
        if (priceId != null) {
            form.add(prefix + "[price]", priceId);
            return;
        }
        form.add(prefix + "[price_data][currency]", plan.currency().toLowerCase(Locale.ROOT));
        form.add(prefix + "[price_data][unit_amount]", cents(plan.price(period)));
        form.add(prefix + "[price_data][recurring][interval]", period == BillingPeriod.ANNUAL ? "year" : "month");
        if (withProductData || product == null) {
            form.add(prefix + "[price_data][product_data][name]", "BuildShield " + plan.name() + " ("
                    + period.label().toLowerCase(Locale.ROOT) + ")");
        } else {
            form.add(prefix + "[price_data][product]", product);
        }
    }

    private static long periodBound(JsonNode subscription, JsonNode item, String field) {
        // Desde la versión 2025-03-31 de la API el periodo está en cada ítem; antes, en la suscripción.
        return item.path(field).isNumber() ? item.path(field).asLong() : subscription.path(field).asLong(0);
    }

    static String cents(BigDecimal amount) {
        return amount.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private JsonNode post(String path, MultiValueMap<String, String> form, String idempotencyKey) {
        return call(() -> http.post().uri(path)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("Idempotency-Key", idempotencyKey)
                .body(form)
                .retrieve()
                .body(String.class));
    }

    private JsonNode get(String path) {
        return call(() -> http.get().uri(path).retrieve().body(String.class));
    }

    private JsonNode call(Supplier<String> request) {
        try {
            String body = circuitBreaker.executeSupplier(() -> {
                try {
                    return request.get();
                } catch (RestClientResponseException ex) {
                    if (ex.getStatusCode().is4xxClientError() && ex.getStatusCode().value() != 429) {
                        throw new StripeRejectedException(ex);
                    }
                    throw ex;
                }
            });
            return json.readTree(body == null ? "{}" : body);
        } catch (CallNotPermittedException ex) {
            throw new UnavailableException("La pasarela de pagos no está disponible por fallas recientes; "
                    + "intenta de nuevo en unos segundos", ex);
        } catch (StripeRejectedException ex) {
            log.warn("Stripe rechazó la llamada ({}): {}", ex.status, ex.getMessage());
            throw new UnavailableException("La pasarela de pagos rechazó la operación (revisar la configuración de "
                    + "Stripe)", ex);
        } catch (RestClientException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            log.warn("Stripe no respondió: {}", ex.getMessage());
            throw new UnavailableException("La pasarela de pagos no respondió a tiempo; intenta de nuevo", ex);
        }
    }

    private void requireConfigured() {
        if (PaymentsProperties.blank(properties.stripe().secretKey())) {
            throw new UnavailableException("La pasarela de pagos no está configurada (STRIPE_SECRET_KEY)");
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Stripe respondió 4xx: el pedido es inválido (no es una falla de disponibilidad). */
    static class StripeRejectedException extends RuntimeException {

        private final int status;

        StripeRejectedException(RestClientResponseException cause) {
            super(cause.getResponseBodyAsString(), cause);
            this.status = cause.getStatusCode().value();
        }
    }
}
