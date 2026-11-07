package pe.buildshield.subscription.application;

import pe.buildshield.subscription.domain.model.BillingPeriod;
import pe.buildshield.subscription.domain.model.PlanCode;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sesión del modo simulado (solo desarrollo local). No se guarda en la base: su identificador lleva la clave del cobro,
 * el plan y la modalidad, igual que los metadatos de una sesión de Stripe. Ejemplo:
 * {@code cs_sim_9f1c2d3e4a5b4c6d8e7f9a0b1c2d3e4f_PLUS_ANNUAL}.
 */
public record SimulatedSession(UUID paymentKey, PlanCode plan, BillingPeriod period) {

    private static final Pattern FORMAT = Pattern.compile(
            "cs_sim_([0-9a-f]{8})([0-9a-f]{4})([0-9a-f]{4})([0-9a-f]{4})([0-9a-f]{12})_([A-Z]+)_([A-Z]+)");

    public SimulatedSession {
        Objects.requireNonNull(paymentKey, "paymentKey");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(period, "period");
    }

    public String value() {
        return "cs_sim_" + paymentKey.toString().replace("-", "") + "_" + plan + "_" + period;
    }

    /** Vacío si no tiene el formato de una sesión simulada. */
    public static Optional<SimulatedSession> parse(String sessionId) {
        Matcher matcher = FORMAT.matcher(sessionId == null ? "" : sessionId);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            UUID key = UUID.fromString(String.join("-", matcher.group(1), matcher.group(2), matcher.group(3),
                    matcher.group(4), matcher.group(5)));
            return Optional.of(new SimulatedSession(key, PlanCode.valueOf(matcher.group(6)),
                    BillingPeriod.valueOf(matcher.group(7))));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
