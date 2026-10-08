package pe.buildshield.subscription.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import pe.buildshield.subscription.application.SubscriptionCommandService;
import pe.buildshield.subscription.shared.messaging.Topics;
import pe.buildshield.subscription.support.MutableClock;
import pe.buildshield.subscription.support.SubscriptionIntegrationTest;
import pe.buildshield.subscription.support.TestDatabase;
import pe.buildshield.subscription.support.TestEvents;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Eventos de subscription contra Kafka embebido y PostgreSQL real: {@code OrganizationRegistered} (repetido) abre
 * una sola prueba gratuita y el outbox publica {@code SubscriptionActivated}; al vencer, {@code SubscriptionExpired}.
 * Los campos son los que lee el consumidor de iam.
 */
@SubscriptionIntegrationTest
class SubscriptionMessagingIT {

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    SubscriptionOutboxPublisher publisher;

    @Autowired
    SubscriptionCommandService commands;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Autowired
    MutableClock clock;

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    @BeforeEach
    void clean() {
        TestDatabase.reset(jdbc);
        clock.setTo(Instant.now());
    }

    @Test
    void organization_registered_twice_opens_one_trial_and_publishes_subscription_activated() throws Exception {
        UUID organization = UUID.randomUUID();
        String registered = TestEvents.organizationRegistered(json, UUID.randomUUID(), organization,
                Instant.now().minusSeconds(5));

        kafka.send(Topics.IAM, organization.toString(), registered);
        kafka.send(Topics.IAM, organization.toString(), registered);
        kafka.send(Topics.IAM, organization.toString(), TestEvents.envelope(json, UUID.randomUUID(), "UserCreated",
                organization, "USER", UUID.randomUUID(), null, Instant.now(), Map.of("userId", UUID.randomUUID())));

        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM processed_events", Long.class) == 2);
        assertThat(jdbc.queryForMap("SELECT plan_code, status, billing_period FROM subscriptions WHERE organization_id = ?",
                organization)).containsEntry("plan_code", "FREE").containsEntry("status", "TRIAL")
                .containsEntry("billing_period", null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscriptions", Long.class)).isEqualTo(1);

        assertThat(publisher.publishPending()).isEqualTo(1);
        JsonNode activated = readSubscriptionTopic(organization, "SubscriptionActivated");
        assertThat(activated.get("aggregateType").asText()).isEqualTo("SUBSCRIPTION");
        assertThat(activated.get("version").asInt()).isEqualTo(1);
        assertThat(activated.at("/payload/organizationId").asText()).isEqualTo(organization.toString());
        assertThat(activated.at("/payload/plan").asText()).isEqualTo("FREE");
        assertThat(activated.at("/payload/status").asText()).isEqualTo("TRIAL");
        Instant validUntil = Instant.parse(activated.at("/payload/validUntil").asText());
        Instant startedAt = Instant.parse(activated.at("/payload/startedAt").asText());
        assertThat(Duration.between(startedAt, validUntil)).isEqualTo(Duration.ofDays(14));
    }

    @Test
    void a_trial_past_its_end_date_expires_once_and_publishes_subscription_expired() throws Exception {
        UUID organization = UUID.randomUUID();
        kafka.send(Topics.IAM, organization.toString(), TestEvents.organizationRegistered(json, UUID.randomUUID(),
                organization, Instant.now()));
        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM subscriptions WHERE organization_id = ?", Long.class, organization) == 1);
        publisher.publishPending();

        clock.advance(Duration.ofDays(15));
        assertThat(commands.expireDue()).isEqualTo(1);
        assertThat(commands.expireDue()).isZero();

        assertThat(jdbc.queryForObject("SELECT status FROM subscriptions WHERE organization_id = ?", String.class,
                organization)).isEqualTo("EXPIRED");
        assertThat(publisher.publishPending()).isEqualTo(1);
        JsonNode expired = readSubscriptionTopic(organization, "SubscriptionExpired");
        assertThat(expired.at("/payload/status").asText()).isEqualTo("EXPIRED");
        assertThat(expired.at("/payload/plan").asText()).isEqualTo("FREE");
        assertThat(expired.get("actorId").isNull()).isTrue();
    }

    private JsonNode readSubscriptionTopic(UUID organizationId, String eventType) throws Exception {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "subscription-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<JsonNode> found = new ArrayList<>();
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<String, String>(props).createConsumer()) {
            consumer.subscribe(List.of(Topics.SUBSCRIPTION));
            long deadline = System.currentTimeMillis() + 20_000;
            while (found.isEmpty() && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    JsonNode envelope = json.readTree(record.value());
                    if (envelope.get("organizationId").asText().equals(organizationId.toString())
                            && envelope.get("eventType").asText().equals(eventType)) {
                        assertThat(record.key()).isEqualTo(envelope.get("aggregateId").asText());
                        found.add(envelope);
                    }
                }
            }
        }
        assertThat(found).hasSize(1);
        return found.get(0);
    }
}
