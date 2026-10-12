package pe.buildshield.subscription.domain.model;

import pe.buildshield.subscription.shared.error.ValidationException;

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

    public static final String PLAN_NOT_PAYABLE = "PLAN_NOT_PAYABLE";

    public Plan {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(monthlyPrice, "monthlyPrice");
        Objects.requireNonNull(annualPrice, "annualPrice");
    }

    public boolean unlimited() {
        return maxActiveWorksites == null;
    }

    /** Lo que costarían doce pagos mensuales. */
    public BigDecimal twelveMonthsPrice() {
        return monthlyPrice.multiply(BigDecimal.valueOf(12));
    }

    /** Ahorro del pago anual frente a doce pagos mensuales (nunca negativo). */
    public BigDecimal annualSavings() {
        return twelveMonthsPrice().subtract(annualPrice).max(BigDecimal.ZERO);
    }

    /** Descuento del pago anual frente a doce pagos mensuales, en porcentaje con dos decimales. */
    public BigDecimal annualDiscountPercent() {
        BigDecimal twelveMonths = twelveMonthsPrice();
        if (twelveMonths.signum() == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return twelveMonths.subtract(annualPrice).multiply(BigDecimal.valueOf(100))
                .divide(twelveMonths, 2, RoundingMode.HALF_UP);
    }

    /** Precio de un periodo de cobro en esa modalidad. */
    public BigDecimal price(BillingPeriod period) {
        return Objects.requireNonNull(period, "period") == BillingPeriod.ANNUAL ? annualPrice : monthlyPrice;
    }

    /** ¿Se puede pagar? Free es solo la prueba gratuita. */
    public boolean payable() {
        return !trialOnly;
    }

    /** Falla con 400 si el plan no se puede contratar (Free). */
    public Plan requirePayable() {
        if (!payable()) {
            throw new ValidationException(PLAN_NOT_PAYABLE,
                    "El plan " + name + " es solo la prueba gratuita: elige Plus o Pro");
        }
        return this;
    }

    /** ¿El plan admite esta cantidad de obras activas? */
    public boolean allowsActiveWorksites(int activeWorksites) {
        return unlimited() || activeWorksites <= maxActiveWorksites;
    }
}
