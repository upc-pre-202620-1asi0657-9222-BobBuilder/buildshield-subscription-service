package pe.buildshield.subscription;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import pe.buildshield.subscription.support.SubscriptionIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** La especificación OpenAPI es pública y documenta los endpoints de subscription con su esquema de seguridad. */
@SubscriptionIntegrationTest
@AutoConfigureMockMvc
class OpenApiDocumentationIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void api_docs_are_public_and_describe_the_subscription_endpoints() throws Exception {
        JsonNode spec = json.readTree(mvc.perform(get("/api/v1/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(spec.path("paths").fieldNames()).toIterable()
                .contains("/api/v1/plans", "/api/v1/subscriptions/current", "/api/v1/subscriptions/checkout",
                        "/api/v1/subscriptions/checkout/{sessionId}/simulated-payment",
                        "/api/v1/subscriptions/current/billing-period", "/api/v1/subscriptions/webhooks/stripe");
        assertThat(spec.at("/components/securitySchemes/bearer/scheme").asText()).isEqualTo("bearer");
        assertThat(spec.at("/paths/~1api~1v1~1plans/get/summary").asText()).isEqualTo("Listar los planes (público)");
        assertThat(spec.at("/paths/~1api~1v1~1plans/get/security").isMissingNode()).isTrue();
    }
}
