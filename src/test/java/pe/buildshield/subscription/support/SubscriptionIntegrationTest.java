package pe.buildshield.subscription.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Servicio completo contra PostgreSQL real (Testcontainers o la base temporal externa explícita
 * {@code subscription_it}) y Kafka embebido ({@code @EmbeddedKafka}, KRaft) con los tópicos que usa subscription.
 * El reloj es un {@link MutableClock} para probar el vencimiento.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@ActiveProfiles("test")
@Import({ContainersConfig.class, TestClockConfig.class})
@EmbeddedKafka(kraft = true, partitions = 3, topics = {"buildshield.subscription", "buildshield.iam"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        // Un solo segmento pequeño para los offsets de los grupos: el grupo se forma en segundos aunque el disco sea lento.
        brokerProperties = {"offsets.topic.num.partitions=1", "offsets.topic.replication.factor=1",
                "log.index.size.max.bytes=1048576"})
public @interface SubscriptionIntegrationTest {
}
