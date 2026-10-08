package pe.buildshield.subscription.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Plan del catálogo. Todos los planes tienen las mismas funciones; cambian el límite de obras activas y el precio.
 *
 * @param maxActiveWorksites nulo = obras ilimitadas
 * @param trialOnly          true solo en Free: no se paga, solo existe como prueba gratuita
 */
public record Plan(PlanCode code, String name, String description, Integer maxActiveWorksites, boolean trialOnly,
        BigDecimal monthlyPrice, BigDecimal annualPrice, String currency) {

    public Plan {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(monthlyPrice, "monthlyPrice");
        Objects.requireNonNull(annualPrice, "annualPrice");
    }

    public boolean unlimited() {
        return maxActiveWorksites == null;
    }

    /** Descuento del pago anual frente a doce pagos mensuales, en porcentaje con dos decimales. */
    public BigDecimal annualDiscountPercent() {
        BigDecimal twelveMonths = monthlyPrice.multiply(BigDecimal.valueOf(12));
        if (twelveMonths.signum() == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return twelveMonths.subtract(annualPrice).multiply(BigDecimal.valueOf(100))
                .divide(twelveMonths, 2, RoundingMode.HALF_UP);
    }

    /** ¿El plan admite esta cantidad de obras activas? */
    public boolean allowsActiveWorksites(int activeWorksites) {
        return unlimited() || activeWorksites <= maxActiveWorksites;
    }
}
