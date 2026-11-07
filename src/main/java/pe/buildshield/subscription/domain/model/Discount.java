package pe.buildshield.subscription.domain.model;

import pe.buildshield.subscription.shared.error.ValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Descuento aplicable al pago anual de un plan frente a doce mensualidades (C4 pág. 28).
 * Objeto de valor / agregado del dominio puro sin dependencias de frameworks (RNF18).
 *
 * @param percentage porcentaje de descuento (0 a 100)
 */
public record Discount(BigDecimal percentage) {

    public static final String INVALID_DISCOUNT = "INVALID_DISCOUNT";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    public static final Discount ZERO = new Discount(BigDecimal.ZERO);

    public Discount {
        Objects.requireNonNull(percentage, "percentage");
        if (percentage.compareTo(BigDecimal.ZERO) < 0 || percentage.compareTo(HUNDRED) > 0) {
            throw new ValidationException(INVALID_DISCOUNT,
                    "El porcentaje de descuento anual debe estar entre 0 y 100, se recibió: " + percentage);
        }
    }

    public static Discount of(BigDecimal percentage) {
        return new Discount(percentage);
    }

    /**
     * Calcula el precio anual a partir del precio mensual aplicando el porcentaje de descuento:
     * {@code monthlyPrice * 12 * (1 - pct/100)}, redondeado al sol más cercano con escala 2.
     */
    public BigDecimal calculateAnnualPrice(BigDecimal monthlyPrice) {
        Objects.requireNonNull(monthlyPrice, "monthlyPrice");
        BigDecimal twelveMonths = monthlyPrice.multiply(BigDecimal.valueOf(12));
        return twelveMonths.multiply(HUNDRED.subtract(percentage))
                .divide(HUNDRED, 0, RoundingMode.HALF_UP)
                .setScale(2);
    }

    /**
     * Ahorro del pago anual frente a doce mensualidades: nunca negativo.
     */
    public BigDecimal calculateSavings(BigDecimal monthlyPrice) {
        Objects.requireNonNull(monthlyPrice, "monthlyPrice");
        BigDecimal twelveMonths = monthlyPrice.multiply(BigDecimal.valueOf(12));
        return twelveMonths.subtract(calculateAnnualPrice(monthlyPrice)).max(BigDecimal.ZERO);
    }

    /**
     * Porcentaje formateado con dos decimales.
     */
    public BigDecimal percent() {
        return percentage.setScale(2, RoundingMode.HALF_UP);
    }
}
