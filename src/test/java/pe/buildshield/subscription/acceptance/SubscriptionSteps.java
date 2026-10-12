package pe.buildshield.subscription.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.es.Cuando;
import io.cucumber.java.es.Dado;
import io.cucumber.java.es.Entonces;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import pe.buildshield.subscription.application.SubscriptionCommandService;
import pe.buildshield.subscription.infrastructure.messaging.SubscriptionOutboxPublisher;
import pe.buildshield.subscription.shared.messaging.Topics;
import pe.buildshield.subscription.shared.security.JwtTokenIssuer;
import pe.buildshield.subscription.shared.tenant.TenantInfo;
import pe.buildshield.subscription.support.MutableClock;
import pe.buildshield.subscription.support.TestEvents;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.within;
import static pe.buildshield.subscription.acceptance.ApiRequest.get;
import static pe.buildshield.subscription.acceptance.ApiRequest.post;
import static pe.buildshield.subscription.acceptance.ApiRequest.put;

/**
 * Pasos de US08 (prueba gratuita y planes), US09 (estado), US07 y US09 del Sprint 4 (pago con Stripe Checkout y
 * webhook firmado), US11 (consulta de modalidad y cobro) y US12 (cambio de modalidad).
 */
public class SubscriptionSteps {

    private static final Duration EVENT_TIMEOUT = Duration.ofSeconds(30);

    /** Secreto de webhook del perfil test (application-test.yml). No es una clave real. */
    private static final String WEBHOOK_SECRET = "whsec_test_only_not_a_real_secret";

    @Autowired
    ScenarioSession session;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtTokenIssuer issuer;

    @Autowired
    ObjectMapper json;

    @Autowired
    MutableClock clock;

    @Autowired
    SubscriptionCommandService commands;

    @Autowired
    SubscriptionOutboxPublisher publisher;

    private UUID organizationId;

    /** Usuario de la sesión: el token se emite en cada petición con el reloj del escenario. */
    private TenantInfo me;

    /** Última sesión de pago abierta y último webhook enviado (cuerpo). */
    private String checkoutSessionId;
    private String previousCheckoutSessionId;
    private String lastWebhook;

    @Dado("que la organización {string} se registró en BuildShield")
    public void organizationRegistered(String legalName) {
        organizationId = UUID.randomUUID();
        kafka.send(Topics.IAM, organizationId.toString(), TestEvents.organizationRegistered(json, UUID.randomUUID(),
                organizationId, clock.instant()));
        await().atMost(EVENT_TIMEOUT).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM subscriptions WHERE organization_id = ?", Long.class, organizationId) == 1);
    }

    @Dado("que el evento de registro de la organización llegó dos veces")
    public void registrationRepeated() {
        kafka.send(Topics.IAM, organizationId.toString(), TestEvents.organizationRegistered(json, UUID.randomUUID(),
                organizationId, clock.instant()));
        await().atMost(EVENT_TIMEOUT).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM processed_events", Long.class) >= 2);
    }

    @Dado("que inicié sesión como {string} de la organización")
    public void signedIn(String role) {
        me = new TenantInfo(organizationId, UUID.randomUUID(), role(role));
    }

    @Dado("que pasaron {int} días")
    public void daysPassed(int days) {
        clock.advance(Duration.ofDays(days));
    }

    @Cuando("se revisan los vencimientos")
    public void expiryCheck() {
        commands.expireDue();
    }

    @Cuando("consulto la suscripción de mi organización")
    public void consultSubscription() throws Exception {
        refreshToken();
        session.send(get("/api/v1/subscriptions/current"), null);
    }

    @Cuando("consulto los planes")
    public void consultPlans() throws Exception {
        refreshToken();
        session.send(get("/api/v1/plans"), null);
    }

    @Entonces("la suscripción está en {string} con el plan {string} y le quedan {int} días")
    public void subscriptionIs(String statusLabel, String plan, int days) throws Exception {
        session.expectStatus(200);
        JsonNode body = session.body();
        assertThat(body.path("statusLabel").asText()).isEqualTo(statusLabel);
        assertThat(body.path("planName").asText()).isEqualTo(plan);
        assertThat(body.path("daysRemaining").asInt()).isEqualTo(days);
    }

    @Entonces("la cuenta {string} en solo lectura")
    public void readOnly(String state) throws Exception {
        assertThat(session.body().path("readOnly").asBoolean()).isEqualTo(state.equals("queda"));
    }

    @Entonces("la organización tiene una sola suscripción")
    public void onlyOne() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscriptions WHERE organization_id = ?", Long.class,
                organizationId)).isEqualTo(1);
    }

    @Entonces("se avisa a los demás servicios con el evento {string} en estado {string}")
    public void announced(String eventType, String status) {
        publisher.publishPending();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_events WHERE organization_id = ? AND event_type = ?
                  AND payload ->> 'status' = ? AND published_at IS NOT NULL""", Long.class, organizationId, eventType,
                status)).isEqualTo(1);
    }

    @Entonces("veo los planes {string} en ese orden")
    public void plansAre(String codes) throws Exception {
        session.expectStatus(200);
        JsonNode body = session.body();
        String[] expected = codes.split(",\\s*");
        assertThat(body.size()).isEqualTo(expected.length);
        for (int i = 0; i < expected.length; i++) {
            assertThat(body.get(i).path("name").asText()).isEqualTo(expected[i]);
        }
    }

    @Entonces("el plan {string} permite {string} obras activas")
    public void planLimit(String name, String limit) throws Exception {
        JsonNode plan = plan(name);
        if (limit.equals("ilimitadas")) {
            assertThat(plan.path("unlimitedWorksites").asBoolean()).isTrue();
        } else {
            assertThat(plan.path("maxActiveWorksites").asInt()).isEqualTo(Integer.parseInt(limit));
        }
    }

    @Entonces("el plan {string} cuesta {string} al mes y {string} al año")
    public void planPrice(String name, String monthly, String annual) throws Exception {
        JsonNode plan = plan(name);
        assertThat(plan.path("monthlyPrice").decimalValue()).isEqualByComparingTo(monthly);
        assertThat(plan.path("annualPrice").decimalValue()).isEqualByComparingTo(annual);
        assertThat(plan.path("annualDiscountPercent").decimalValue()).isPositive();
    }

    @Entonces("el plan {string} es solo de prueba por {int} días")
    public void trialPlan(String name, int days) throws Exception {
        JsonNode plan = plan(name);
        assertThat(plan.path("trialOnly").asBoolean()).isTrue();
        assertThat(plan.path("trialDays").asInt()).isEqualTo(days);
    }

    @Entonces("la respuesta tiene estado {int}")
    public void responseStatus(int status) {
        session.expectStatus(status);
    }

    @Cuando("consulto los planes sin iniciar sesión")
    public void consultPlansAnonymously() throws Exception {
        session.lastResponse(session.perform(get("/api/v1/plans"), null, null));
    }

    @Entonces("el plan {string} ahorra {string} al año, un {string} % frente a {int} pagos mensuales")
    public void planSavings(String name, String savings, String percent, int months) throws Exception {
        JsonNode plan = plan(name);
        assertThat(plan.path("annualSavings").decimalValue()).isEqualByComparingTo(savings);
        assertThat(plan.path("annualDiscountPercent").decimalValue()).isEqualByComparingTo(percent);
        assertThat(plan.path("twelveMonthsPrice").decimalValue())
                .isEqualByComparingTo(plan.path("monthlyPrice").decimalValue().multiply(
                        java.math.BigDecimal.valueOf(months)));
    }

    @Cuando("pido pagar el plan {string} en modalidad {string}")
    public void requestCheckout(String plan, String period) throws Exception {
        refreshToken();
        session.send(post("/api/v1/subscriptions/checkout"), Map.of("plan", plan, "billingPeriod", period));
        if (session.status() == 201) {
            previousCheckoutSessionId = checkoutSessionId;
            checkoutSessionId = session.body().path("sessionId").asText();
        }
    }

    @Dado("que pedí pagar el plan {string} en modalidad {string}")
    public void checkoutRequested(String plan, String period) throws Exception {
        requestCheckout(plan, period);
        session.expectStatus(201);
    }

    @Entonces("recibo una sesión de pago por {string} lista para redirigir")
    public void checkoutReceived(String amount) throws Exception {
        session.expectStatus(201);
        JsonNode body = session.body();
        assertThat(body.path("amount").decimalValue()).isEqualByComparingTo(amount);
        assertThat(body.path("checkoutUrl").asText()).contains(checkoutSessionId);
        assertThat(body.path("simulated").asBoolean()).isTrue();
        assertThat(body.path("status").asText()).isEqualTo("OPEN");
        assertThat(body.path("reused").asBoolean()).isFalse();
    }

    @Entonces("la sesión de pago es la misma de antes")
    public void sameCheckout() throws Exception {
        session.expectStatus(201);
        assertThat(session.body().path("reused").asBoolean()).isTrue();
        assertThat(checkoutSessionId).isEqualTo(previousCheckoutSessionId);
    }

    @Cuando("la pasarela confirma el pago de la sesión con un webhook firmado")
    public void signedWebhook() throws Exception {
        lastWebhook = """
                {"id":"evt_%s","object":"event","type":"checkout.session.completed","data":{"object":{
                "id":"%s","object":"checkout.session","mode":"subscription","payment_status":"paid",
                "client_reference_id":"%s","customer":"cus_test_1","subscription":"sub_test_%s"}}}"""
                .formatted(UUID.randomUUID().toString().replace("-", ""), checkoutSessionId, organizationId,
                        UUID.randomUUID().toString().replace("-", ""));
        sendWebhook(lastWebhook, WEBHOOK_SECRET);
    }

    @Cuando("la pasarela reenvía el mismo webhook")
    public void resendWebhook() throws Exception {
        session.expectStatus(200);
        sendWebhook(lastWebhook, WEBHOOK_SECRET);
    }

    @Cuando("llega un webhook de pago con una firma falsa")
    public void forgedWebhook() throws Exception {
        String payload = """
                {"id":"evt_falso","type":"checkout.session.completed","data":{"object":{"id":"%s",
                "payment_status":"paid","client_reference_id":"%s"}}}""".formatted(checkoutSessionId, organizationId);
        sendWebhook(payload, "whsec_de_un_atacante");
    }

    @Entonces("el webhook responde {string}")
    public void webhookResult(String result) throws Exception {
        session.expectStatus(200);
        assertThat(session.body().path("received").asBoolean()).isTrue();
        assertThat(session.body().path("result").asText()).isEqualTo(result);
    }

    @Entonces("la suscripción se activó una sola vez")
    public void activatedOnce() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_events WHERE organization_id = ? AND event_type = 'SubscriptionActivated'
                  AND payload ->> 'reason' = 'PAYMENT_CONFIRMED'""", Long.class, organizationId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM subscriptions WHERE organization_id = ?", String.class,
                organizationId)).isEqualTo("ACTIVE");
    }

    @Entonces("la suscripción sigue en {string}")
    public void stillIn(String statusLabel) throws Exception {
        assertThat(current().path("statusLabel").asText()).isEqualTo(statusLabel);
    }

    @Cuando("completo el pago simulado de la sesión")
    public void simulatedPayment() throws Exception {
        refreshToken();
        session.send(post("/api/v1/subscriptions/checkout/" + checkoutSessionId + "/simulated-payment"), null);
    }

    @Dado("que la organización pagó el plan {string} en modalidad {string}")
    public void organizationPaid(String plan, String period) throws Exception {
        checkoutRequested(plan, period);
        simulatedPayment();
        session.expectStatus(200);
    }

    @Entonces("la suscripción está activa con el plan {string} por {int} meses")
    public void activeFor(String plan, int months) throws Exception {
        session.expectStatus(200);
        JsonNode body = session.body();
        assertThat(body.path("statusLabel").asText()).isEqualTo("Activa");
        assertThat(body.path("planName").asText()).isEqualTo(plan);
        assertThat(body.path("readOnly").asBoolean()).isFalse();
        Instant expected = clock.instant().atZone(ZoneOffset.UTC).plusMonths(months).toInstant();
        assertThat(Instant.parse(body.path("validUntil").asText())).isCloseTo(expected, within(Duration.ofSeconds(1)));
        assertThat(Instant.parse(body.path("currentPeriodStart").asText()))
                .isCloseTo(clock.instant(), within(Duration.ofSeconds(1)));
    }

    @Entonces("la modalidad es {string} con la próxima fecha de cobro al fin del periodo")
    public void billingPeriodIs(String label) throws Exception {
        JsonNode body = current();
        assertThat(body.path("billingPeriodLabel").asText()).isEqualTo(label);
        assertThat(body.path("nextBillingAt").asText()).isEqualTo(body.path("validUntil").asText());
    }

    @Entonces("no hay cambio de modalidad programado")
    public void noPendingChange() throws Exception {
        JsonNode body = current();
        assertThat(body.path("pendingBillingPeriod").isNull()).isTrue();
        assertThat(body.path("pendingChangeAt").isNull()).isTrue();
    }

    @Cuando("cambio la modalidad a {string}")
    public void changeBillingPeriod(String period) throws Exception {
        refreshToken();
        session.send(put("/api/v1/subscriptions/current/billing-period"), Map.of("billingPeriod", period));
    }

    @Dado("que cambié la modalidad a {string}")
    public void billingPeriodChanged(String period) throws Exception {
        changeBillingPeriod(period);
        session.expectStatus(200);
    }

    @Entonces("el cambio queda {string} para la próxima fecha de cobro")
    public void changeOutcome(String outcome) throws Exception {
        session.expectStatus(200);
        JsonNode body = session.body();
        assertThat(body.path("outcome").asText()).isEqualTo(outcome);
        JsonNode subscription = body.path("subscription");
        if (outcome.equals("SCHEDULED")) {
            assertThat(subscription.path("pendingChangeAt").asText())
                    .isEqualTo(subscription.path("nextBillingAt").asText());
            assertThat(subscription.path("pendingBillingPeriod").asText()).isNotBlank();
        } else {
            assertThat(subscription.path("pendingBillingPeriod").isNull()).isTrue();
        }
    }

    @Entonces("la operación es rechazada con estado {int}")
    public void rejected(int status) {
        session.expectStatus(status);
    }

    @Entonces("la operación es rechazada con estado {int} y código {string}")
    public void rejectedWithCode(int status, String code) throws Exception {
        session.expectStatus(status);
        assertThat(session.body().path("code").asText()).isEqualTo(code);
    }

    private JsonNode current() throws Exception {
        refreshToken();
        ApiResponse response = session.perform(get("/api/v1/subscriptions/current"), null, session.accessToken());
        assertThat(response.status()).as("respuesta: %s", response.body()).isEqualTo(200);
        return session.read(response);
    }

    /** Envía el webhook sin token, firmado como Stripe: {@code t=<segundos>,v1=HMAC-SHA256("t.cuerpo")}. */
    private void sendWebhook(String payload, String secret) throws Exception {
        long timestamp = clock.instant().getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal((timestamp + "." + payload)
                .getBytes(StandardCharsets.UTF_8)));
        session.lastResponse(session.performRaw(post("/api/v1/subscriptions/webhooks/stripe"), payload,
                Map.of("Stripe-Signature", "t=" + timestamp + ",v1=" + signature)));
    }

    private void refreshToken() {
        if (me != null) {
            session.accessToken(issuer.issue(me).value());
        }
    }

    private JsonNode plan(String name) throws Exception {
        for (JsonNode plan : session.body()) {
            if (plan.path("name").asText().equals(name)) {
                return plan;
            }
        }
        throw new AssertionError("No está el plan " + name);
    }

    private static String role(String displayName) {
        return switch (displayName.trim().toLowerCase()) {
            case "administrador" -> "ADMINISTRATOR";
            case "encargado de obra" -> "SITE_MANAGER";
            case "encargado de almacén", "encargado de almacen" -> "WAREHOUSE_MANAGER";
            default -> displayName;
        };
    }
}
