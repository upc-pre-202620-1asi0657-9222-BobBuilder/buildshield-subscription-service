package pe.buildshield.subscription.application;

import org.junit.jupiter.api.Test;
import pe.buildshield.subscription.domain.model.BillingPeriod;
import pe.buildshield.subscription.domain.model.PlanCode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatedSessionTest {

    @Test
    void the_identifier_carries_the_key_the_plan_and_the_period() {
        UUID key = UUID.fromString("9f1c2d3e-4a5b-4c6d-8e7f-9a0b1c2d3e4f");
        SimulatedSession session = new SimulatedSession(key, PlanCode.PLUS, BillingPeriod.ANNUAL);

        assertThat(session.value()).isEqualTo("cs_sim_9f1c2d3e4a5b4c6d8e7f9a0b1c2d3e4f_PLUS_ANNUAL");
        assertThat(SimulatedSession.parse(session.value())).contains(session);
    }

    @Test
    void anything_else_is_not_a_simulated_session() {
        assertThat(SimulatedSession.parse(null)).isEmpty();
        assertThat(SimulatedSession.parse("cs_test_a1B2")).isEmpty();
        assertThat(SimulatedSession.parse("cs_sim_9f1c2d3e4a5b4c6d8e7f9a0b1c2d3e4f_GOLD_ANNUAL")).isEmpty();
        assertThat(SimulatedSession.parse("cs_sim_9f1c2d3e4a5b4c6d8e7f9a0b1c2d3e4f_PLUS_WEEKLY")).isEmpty();
    }
}
