package pe.buildshield.subscription;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pe.buildshield.subscription.application.SubscriptionQueryService;
import pe.buildshield.subscription.domain.model.Plan;
import pe.buildshield.subscription.domain.model.PlanCode;
import pe.buildshield.subscription.domain.model.SubscriptionStatus;
import pe.buildshield.subscription.shared.security.JwtTokenIssuer;
import pe.buildshield.subscription.shared.security.RevokedTokenStore;
import pe.buildshield.subscription.shared.tenant.TenantInfo;
import pe.buildshield.subscription.support.WebSliceTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Capa web con la seguridad real: cualquier rol de la organización consulta planes y suscripción; sin token, 401. */
@WebMvcTest
@WebSliceTest
class RoleAuthorizationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtTokenIssuer issuer;

    @MockitoBean
    SubscriptionQueryService queries;

    @MockitoBean
    RevokedTokenStore revokedTokens;

    @Test
    void every_role_reads_the_plans_and_the_current_subscription() throws Exception {
        Plan plus = new Plan(PlanCode.PLUS, "Plus", "Hasta 5 obras", 5, false, new BigDecimal("299.00"),
                new BigDecimal("2990.00"), "PEN");
        when(queries.plans()).thenReturn(List.of(plus));
        when(queries.trialDays()).thenReturn(14);
        UUID organization = UUID.randomUUID();
        when(queries.current()).thenReturn(new SubscriptionQueryService.SubscriptionView(UUID.randomUUID(),
                organization, plus, SubscriptionStatus.ACTIVE, null, Instant.now(), Instant.now().plusSeconds(86_400),
                1, false));

        for (String role : List.of("ADMINISTRATOR", "WAREHOUSE_MANAGER", "SITE_MANAGER")) {
            String token = issuer.issue(new TenantInfo(organization, UUID.randomUUID(), role)).value();
            mvc.perform(get("/api/v1/plans").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].code").value("PLUS"))
                    .andExpect(jsonPath("$[0].trialDays").doesNotExist())
                    .andExpect(jsonPath("$[0].annualDiscountPercent").value(16.67));
            mvc.perform(get("/api/v1/subscriptions/current").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.plan").value("PLUS"))
                    .andExpect(jsonPath("$.statusLabel").value("Activa"))
                    .andExpect(jsonPath("$.readOnly").value(false));
        }
    }

    @Test
    void without_token_the_api_answers_401() throws Exception {
        mvc.perform(get("/api/v1/plans")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/subscriptions/current")).andExpect(status().isUnauthorized());
    }
}
