package pe.buildshield.subscription.domain.model;

import pe.buildshield.subscription.shared.error.ValidationException;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Plan del catálogo. Todos los planes tienen las mismas funciones; cambian el límite de obras activas y el precio.
 * El ER guarda el precio mensual y el descuento anual: el nombre, la descripción y la moneda salen del código.
 *
 * @param maxActiveWorksites nulo = obras ilimitadas
 * @param discount           descuento del pago anual frente a doce pagos mensuales (objeto de valor)
 */
public record Plan(PlanCode code, Integer maxActiveWorksites, BigDecimal monthlyPrice, Discount discount) {

    public static final String PLAN_NOT_PAYABLE = "PLAN_NOT_PAYABLE";

    /** Todos los precios están en soles. */
    public static final String CURRENCY = "PEN";

    public Plan {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(monthlyPrice, "monthlyPrice");
        Objects.requireNonNull(discount, "discount");
    }

    public Plan(PlanCode code, Integer maxActiveWorksites, BigDecimal monthlyPrice, BigDecimal annualDiscountPct) {
        this(code, maxActiveWorksites, monthlyPrice, Discount.of(annualDiscountPct));
    }

    public String name() {
        return code.displayName();
    }

    public String description() {
        return code.description();
    }

    public String currency() {
        return CURRENCY;
    }

    /** {@code true} solo en Free: no se paga, solo existe como prueba gratuita. */
    public boolean trialOnly() {
        return code == PlanCode.FREE;
    }

    public boolean unlimited() {
        return maxActiveWorksites == null;
    }

    /** Lo que costarían doce pagos mensuales. */
    public BigDecimal twelveMonthsPrice() {
        return monthlyPrice.multiply(BigDecimal.valueOf(12));
    }

    /**
     * Precio de un año: doce meses menos el descuento, redondeado al sol. Con dos decimales el descuento es
     * aproximado (S/ 299 al 16,67 % da 2 989,88) y el precio publicado es entero (S/ 2 990).
     */
    public BigDecimal annualPrice() {
        return discount.calculateAnnualPrice(monthlyPrice);
    }

    /** Ahorro del pago anual frente a doce pagos mensuales (nunca negativo). */
    public BigDecimal annualSavings() {
        return discount.calculateSavings(monthlyPrice);
    }

    /** Descuento del pago anual frente a doce pagos mensuales, en porcentaje con dos decimales. */
    public BigDecimal annualDiscountPercent() {
        return discount.percent();
    }

    /** Porcentaje de descuento anual directo. */
    public BigDecimal annualDiscountPct() {
        return discount.percentage();
    }

    /** Precio de un periodo de cobro en esa modalidad. */
    public BigDecimal price(BillingPeriod period) {
        return Objects.requireNonNull(period, "period") == BillingPeriod.ANNUAL ? annualPrice() : monthlyPrice;
    }

    /** ¿Se puede pagar? Free es solo la prueba gratuita. */
    public boolean payable() {
        return !trialOnly();
    }

    /** Falla con 400 si el plan no se puede contratar (Free). */
    public Plan requirePayable() {
        if (!payable()) {
            throw new ValidationException(PLAN_NOT_PAYABLE,
                    "El plan " + name() + " es solo la prueba gratuita: elige Plus o Pro");
        }
        return this;
    }

    /** ¿El plan admite esta cantidad de obras activas? */
    public boolean allowsActiveWorksites(int activeWorksites) {
        return unlimited() || activeWorksites <= maxActiveWorksites;
    }
}
