package pe.buildshield.subscription.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Instant;

/** Reloj de prueba compartido por las pruebas de integración: reemplaza al reloj del kernel. */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfig {

    @Bean
    MutableClock clock() {
        return new MutableClock(Instant.now());
    }
}
