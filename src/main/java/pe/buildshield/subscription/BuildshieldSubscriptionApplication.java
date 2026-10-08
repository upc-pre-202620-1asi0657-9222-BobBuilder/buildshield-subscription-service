package pe.buildshield.subscription;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Suscripción y Pagos de BuildShield (microservicio, puerto 8086, base subscription_db). */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BuildshieldSubscriptionApplication {

    public static void main(String[] args) {
        SpringApplication.run(BuildshieldSubscriptionApplication.class, args);
    }
}
