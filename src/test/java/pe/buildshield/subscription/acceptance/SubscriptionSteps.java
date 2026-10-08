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

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static pe.buildshield.subscription.acceptance.ApiRequest.get;

/** Pasos de US08 (prueba gratuita) y US09 (estado de la suscripción y planes). */
public class SubscriptionSteps {

    private static final Duration EVENT_TIMEOUT = Duration.ofSeconds(30);

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
