package pe.buildshield.subscription.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanTest {

    private final Plan free = new Plan(PlanCode.FREE, "Free", "Prueba", null, true, BigDecimal.ZERO,
            BigDecimal.ZERO, "PEN");
    private final Plan plus = new Plan(PlanCode.PLUS, "Plus", "Hasta 5 obras", 5, false, new BigDecimal("299.00"),
            new BigDecimal("2990.00"), "PEN");
    private final Plan pro = new Plan(PlanCode.PRO, "Pro", "Ilimitadas", null, false, new BigDecimal("699.00"),
            new BigDecimal("6990.00"), "PEN");

    @Test
    void plus_allows_up_to_five_active_worksites_and_pro_has_no_limit() {
        assertThat(plus.unlimited()).isFalse();
        assertThat(plus.allowsActiveWorksites(5)).isTrue();
        assertThat(plus.allowsActiveWorksites(6)).isFalse();
        assertThat(pro.unlimited()).isTrue();
        assertThat(pro.allowsActiveWorksites(500)).isTrue();
    }

    @Test
    void annual_payment_has_a_discount_against_twelve_months() {
        assertThat(plus.annualDiscountPercent()).isEqualByComparingTo("16.67");
        assertThat(pro.annualDiscountPercent()).isEqualByComparingTo("16.67");
        assertThat(free.annualDiscountPercent()).isEqualByComparingTo("0");
    }

    @Test
    void code_and_prices_are_required() {
        assertThatThrownBy(() -> new Plan(null, "X", "X", null, false, BigDecimal.ONE, BigDecimal.ONE, "PEN"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Plan(PlanCode.PRO, "X", "X", null, false, null, BigDecimal.ONE, "PEN"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Plan(PlanCode.PRO, "X", "X", null, false, BigDecimal.ONE, null, "PEN"))
                .isInstanceOf(NullPointerException.class);
    }
}
