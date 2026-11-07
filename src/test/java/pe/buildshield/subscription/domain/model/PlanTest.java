package pe.buildshield.subscription.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanTest {

    private final Plan free = new Plan(PlanCode.FREE, null, BigDecimal.ZERO, BigDecimal.ZERO);
    private final Plan plus = new Plan(PlanCode.PLUS, 5, new BigDecimal("299.00"), new BigDecimal("16.67"));
    private final Plan pro = new Plan(PlanCode.PRO, null, new BigDecimal("699.00"), new BigDecimal("16.67"));

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
        assertThatThrownBy(() -> new Plan(null, null, BigDecimal.ONE, BigDecimal.ONE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Plan(PlanCode.PRO, null, null, BigDecimal.ONE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Plan(PlanCode.PRO, null, BigDecimal.ONE, (BigDecimal) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Plan(PlanCode.PRO, null, BigDecimal.ONE, (Discount) null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void name_description_and_currency_come_from_the_code_and_only_free_is_trial_only() {
        assertThat(free.name()).isEqualTo("Free");
        assertThat(plus.name()).isEqualTo("Plus");
        assertThat(pro.description()).isEqualTo("Obras ilimitadas, todas las funciones");
        assertThat(plus.currency()).isEqualTo("PEN");
        assertThat(free.trialOnly()).isTrue();
        assertThat(plus.trialOnly()).isFalse();
    }

    @Test
    void annual_savings_against_twelve_monthly_payments_and_price_per_period() {
        assertThat(plus.twelveMonthsPrice()).isEqualByComparingTo("3588.00");
        assertThat(plus.annualSavings()).isEqualByComparingTo("598.00");
        assertThat(pro.annualSavings()).isEqualByComparingTo("1398.00");
        assertThat(free.annualSavings()).isEqualByComparingTo("0");
        assertThat(plus.price(BillingPeriod.MONTHLY)).isEqualByComparingTo("299.00");
        assertThat(plus.price(BillingPeriod.ANNUAL)).isEqualByComparingTo("2990.00");
        assertThat(pro.annualPrice()).isEqualByComparingTo("6990.00");
        assertThat(free.annualPrice()).isEqualByComparingTo("0");
        Plan withoutDiscount = new Plan(PlanCode.PRO, null, BigDecimal.TEN, BigDecimal.ZERO);
        assertThat(withoutDiscount.annualPrice()).isEqualByComparingTo("120");
        assertThat(withoutDiscount.annualSavings()).isEqualByComparingTo("0");
    }

    @Test
    void only_paid_plans_can_be_bought() {
        assertThat(plus.requirePayable()).isSameAs(plus);
        assertThat(pro.payable()).isTrue();
        assertThat(free.payable()).isFalse();
        assertThatThrownBy(free::requirePayable)
                .isInstanceOf(pe.buildshield.subscription.shared.error.ValidationException.class)
                .hasFieldOrPropertyWithValue("code", "PLAN_NOT_PAYABLE");
    }

    @Test
    void plan_can_be_created_with_discount_value_object() {
        Discount discount = Discount.of(new BigDecimal("20.00"));
        Plan plan = new Plan(PlanCode.PLUS, 5, new BigDecimal("100.00"), discount);
        assertThat(plan.discount()).isEqualTo(discount);
        assertThat(plan.annualDiscountPct()).isEqualByComparingTo("20.00");
        assertThat(plan.annualPrice()).isEqualByComparingTo("960.00");
        assertThat(plan.annualSavings()).isEqualByComparingTo("240.00");
    }

    @Test
    void billing_periods_end_one_month_or_one_year_later() {
        java.time.Instant start = java.time.Instant.parse("2026-01-31T10:00:00Z");
        assertThat(BillingPeriod.MONTHLY.periodEnd(start)).isEqualTo(java.time.Instant.parse("2026-02-28T10:00:00Z"));
        assertThat(BillingPeriod.ANNUAL.periodEnd(start)).isEqualTo(java.time.Instant.parse("2027-01-31T10:00:00Z"));
        assertThat(BillingPeriod.MONTHLY.label()).isEqualTo("Mensual");
        assertThat(BillingPeriod.ANNUAL.label()).isEqualTo("Anual");
    }
}
