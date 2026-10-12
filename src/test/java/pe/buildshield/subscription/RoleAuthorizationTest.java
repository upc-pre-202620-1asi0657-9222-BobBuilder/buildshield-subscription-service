package pe.buildshield.subscription;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pe.buildshield.subscription.application.InvalidPaymentWebhookException;
import pe.buildshield.subscription.application.PaymentGateway;
import pe.buildshield.subscription.application.SubscriptionCommandService;
import pe.buildshield.subscription.application.SubscriptionQueryService;
import pe.buildshield.subscription.domain.model.BillingPeriod;
import pe.buildshield.subscription.domain.model.PaymentCheckout;
import pe.buildshield.subscription.domain.model.PaymentProvider;
import pe.buildshield.subscription.domain.model.Plan;
import pe.buildshield.subscription.domain.model.PlanCode;
import pe.buildshield.subscription.domain.model.Subscription.BillingPeriodChange;
import pe.buildshield.subscription.domain.model.SubscriptionStatus;
import pe.buildshield.subscription.shared.security.JwtTokenIssuer;
import pe.buildshield.subscription.shared.security.RevokedTokenStore;
import pe.buildshield.subscription.shared.tenant.TenantInfo;
import pe.buildshield.subscription.support.WebSliceTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Capa web con la seguridad real: los planes y el webhook de Stripe son públicos; cualquier rol consulta su
 * suscripción; solo el administrador paga y cambia la modalidad; sin token, 401.
 */
@WebMvcTest
@WebSliceTest
class RoleAuthorizationTest {

    private static final Plan PLUS = new Plan(PlanCode.PLUS, "Plus", "Hasta 5 obras", 5, false,
            new BigDecimal("299.00"), new BigDecimal("2990.00"), "PEN");

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtTokenIssuer issuer;

    @MockitoBean
    SubscriptionQueryService queries;

    @MockitoBean
    SubscriptionCommandService commands;

    @MockitoBean
    RevokedTokenStore revokedTokens;

    private final UUID organization = UUID.randomUUID();

    @Test
    void every_role_reads_the_current_subscription_and_anyone_reads_the_plans() throws Exception {
        when(queries.plans()).thenReturn(List.of(PLUS));
        when(queries.trialDays()).thenReturn(14);
        when(queries.current()).thenReturn(activeView());

        mvc.perform(get("/api/v1/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("PLUS"))
                .andExpect(jsonPath("$[0].trialDays").doesNotExist())
                .andExpect(jsonPath("$[0].annualDiscountPercent").value(16.67))
                .andExpect(jsonPath("$[0].annualSavings").value(598.00))
                .andExpect(jsonPath("$[0].twelveMonthsPrice").value(3588.00))
                .andExpect(jsonPath("$[0].annualMonthlyEquivalent").value(249.17));
        for (String role : List.of("ADMINISTRATOR", "WAREHOUSE_MANAGER", "SITE_MANAGER")) {
            mvc.perform(get("/api/v1/subscriptions/current").header("Authorization", bearer(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.plan").value("PLUS"))
                    .andExpect(jsonPath("$.statusLabel").value("Activa"))
                    .andExpect(jsonPath("$.billingPeriod").value("ANNUAL"))
                    .andExpect(jsonPath("$.billingPeriodLabel").value("Anual"))
                    .andExpect(jsonPath("$.nextBillingAt").exists())
                    .andExpect(jsonPath("$.currentPeriodStart").exists())
                    .andExpect(jsonPath("$.readOnly").value(false));
        }
    }

    @Test
    void only_the_administrator_pays_and_changes_the_billing_period() throws Exception {
        PaymentCheckout checkout = PaymentCheckout.open(UUID.randomUUID(), organization, "cs_sim_1", PlanCode.PLUS,
                BillingPeriod.ANNUAL, PaymentProvider.SIMULATED, "http://localhost:4200/suscripcion/pago-simulado",
                Instant.now());
        when(commands.startCheckout(PlanCode.PLUS, BillingPeriod.ANNUAL))
                .thenReturn(new SubscriptionCommandService.CheckoutStarted(checkout, PLUS, false));
        when(commands.changeBillingPeriod(BillingPeriod.MONTHLY)).thenReturn(BillingPeriodChange.SCHEDULED);
        when(queries.current()).thenReturn(activeView());
        String checkoutBody = "{\"plan\":\"PLUS\",\"billingPeriod\":\"ANNUAL\"}";

        for (String role : List.of("WAREHOUSE_MANAGER", "SITE_MANAGER")) {
            mvc.perform(post("/api/v1/subscriptions/checkout").header("Authorization", bearer(role))
                    .contentType(MediaType.APPLICATION_JSON).content(checkoutBody))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/subscriptions/checkout/cs_sim_1/simulated-payment")
                    .header("Authorization", bearer(role))).andExpect(status().isForbidden());
            mvc.perform(put("/api/v1/subscriptions/current/billing-period").header("Authorization", bearer(role))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"billingPeriod\":\"MONTHLY\"}"))
                    .andExpect(status().isForbidden());
        }
        verify(commands, never()).startCheckout(any(), any());

        mvc.perform(post("/api/v1/subscriptions/checkout").header("Authorization", bearer("ADMINISTRATOR"))
                        .contentType(MediaType.APPLICATION_JSON).content(checkoutBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").value("cs_sim_1"))
                .andExpect(jsonPath("$.simulated").value(true))
                .andExpect(jsonPath("$.amount").value(2990.00))
                .andExpect(jsonPath("$.status").value("OPEN"));
        mvc.perform(post("/api/v1/subscriptions/checkout/cs_sim_1/simulated-payment")
                        .header("Authorization", bearer("ADMINISTRATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        verify(commands).completeSimulatedPayment("cs_sim_1");
        mvc.perform(put("/api/v1/subscriptions/current/billing-period").header("Authorization", bearer("ADMINISTRATOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"billingPeriod\":\"MONTHLY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("SCHEDULED"))
                .andExpect(jsonPath("$.subscription.plan").value("PLUS"));
        mvc.perform(post("/api/v1/subscriptions/checkout").header("Authorization", bearer("ADMINISTRATOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"PLUS\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void an_unavailable_gateway_answers_503_with_retry_after() throws Exception {
        when(commands.startCheckout(PlanCode.PRO, BillingPeriod.MONTHLY))
                .thenThrow(new PaymentGateway.UnavailableException("La pasarela de pagos no respondió a tiempo"));

        mvc.perform(post("/api/v1/subscriptions/checkout").header("Authorization", bearer("ADMINISTRATOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"PRO\",\"billingPeriod\":\"MONTHLY\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.code").value("PAYMENT_GATEWAY_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("La pasarela de pagos no respondió a tiempo"));
    }

    @Test
    void the_stripe_webhook_is_public_and_its_signature_is_checked_by_the_service() throws Exception {
        String payload = "{\"id\":\"evt_1\"}";
        when(commands.receiveWebhook(payload, "t=1,v1=abc"))
                .thenReturn(SubscriptionCommandService.WebhookResult.PROCESSED);
        when(commands.receiveWebhook(eq(payload), eq("t=1,v1=mala")))
                .thenThrow(new InvalidPaymentWebhookException("La firma del webhook no es válida"));

        mvc.perform(post("/api/v1/subscriptions/webhooks/stripe").header("Stripe-Signature", "t=1,v1=abc")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true))
                .andExpect(jsonPath("$.result").value("PROCESSED"));
        mvc.perform(post("/api/v1/subscriptions/webhooks/stripe").header("Stripe-Signature", "t=1,v1=mala")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_WEBHOOK_SIGNATURE"));
    }

    @Test
    void without_token_the_api_answers_401() throws Exception {
        mvc.perform(get("/api/v1/subscriptions/current")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/subscriptions/checkout").contentType(MediaType.APPLICATION_JSON)
                .content("{\"plan\":\"PLUS\",\"billingPeriod\":\"ANNUAL\"}")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/subscriptions/current/billing-period").contentType(MediaType.APPLICATION_JSON)
                .content("{\"billingPeriod\":\"ANNUAL\"}")).andExpect(status().isUnauthorized());
    }

    private SubscriptionQueryService.SubscriptionView activeView() {
        Instant start = Instant.now();
        return new SubscriptionQueryService.SubscriptionView(UUID.randomUUID(), organization, PLUS,
                SubscriptionStatus.ACTIVE, BillingPeriod.ANNUAL, start, start.plusSeconds(86_400 * 365L), 365, false,
                start, start.plusSeconds(86_400 * 365L), null, null, PaymentProvider.SIMULATED);
    }

    private String bearer(String role) {
        return "Bearer " + issuer.issue(new TenantInfo(organization, UUID.randomUUID(), role)).value();
    }
}
