package pe.buildshield.subscription.domain.model;

import org.junit.jupiter.api.Test;
import pe.buildshield.subscription.shared.error.ValidationException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscountTest {

    @Test
    void valid_discount_calculates_annual_price_and_savings() {
        Discount discount = Discount.of(new BigDecimal("16.67"));
        BigDecimal monthly = new BigDecimal("299.00");

        assertThat(discount.percent()).isEqualByComparingTo("16.67");
        assertThat(discount.calculateAnnualPrice(monthly)).isEqualByComparingTo("2990.00");
        assertThat(discount.calculateSavings(monthly)).isEqualByComparingTo("598.00");
    }

    @Test
    void zero_discount_gives_twelve_months_price_and_zero_savings() {
        Discount discount = Discount.ZERO;
        BigDecimal monthly = new BigDecimal("100.00");

        assertThat(discount.percent()).isEqualByComparingTo("0.00");
        assertThat(discount.calculateAnnualPrice(monthly)).isEqualByComparingTo("1200.00");
        assertThat(discount.calculateSavings(monthly)).isEqualByComparingTo("0.00");
    }

    @Test
    void hundred_percent_discount_makes_annual_price_zero() {
        Discount discount = Discount.of(new BigDecimal("100"));
        BigDecimal monthly = new BigDecimal("250.00");

        assertThat(discount.percent()).isEqualByComparingTo("100.00");
        assertThat(discount.calculateAnnualPrice(monthly)).isEqualByComparingTo("0.00");
        assertThat(discount.calculateSavings(monthly)).isEqualByComparingTo("3000.00");
    }

    @Test
    void discount_below_zero_or_above_hundred_is_rejected() {
        assertThatThrownBy(() -> Discount.of(new BigDecimal("-0.01")))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("code", Discount.INVALID_DISCOUNT);

        assertThatThrownBy(() -> Discount.of(new BigDecimal("100.01")))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("code", Discount.INVALID_DISCOUNT);

        assertThatThrownBy(() -> Discount.of(null))
                .isInstanceOf(NullPointerException.class);
    }
}
