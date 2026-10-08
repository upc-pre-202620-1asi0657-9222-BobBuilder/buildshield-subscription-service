package pe.buildshield.subscription.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.buildshield.subscription.application.SubscriptionQueryService;
import pe.buildshield.subscription.domain.model.Plan;
import pe.buildshield.subscription.domain.model.PlanCode;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/plans")
@Tag(name = "Planes", description = "Catálogo de planes: Free (solo prueba), Plus (hasta 5 obras activas) y Pro (ilimitadas)")
@SecurityRequirement(name = "bearer")
class PlansController {

    private final SubscriptionQueryService queries;

    PlansController(SubscriptionQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Listar los planes",
            description = "Planes con su límite de obras activas y su precio mensual y anual (anual con descuento). "
                    + "Todos tienen las mismas funciones.")
    @ApiResponse(responseCode = "200", description = "Planes en el orden en que se muestran",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = PlanResource.class)),
                    examples = @ExampleObject(value = SubscriptionApiExamples.PLANS)))
    List<PlanResource> list() {
        int trialDays = queries.trialDays();
        return queries.plans().stream().map(plan -> PlanResource.of(plan, trialDays)).toList();
    }

    @Schema(description = "Plan de suscripción")
    record PlanResource(PlanCode code, String name, String description,
            @Schema(description = "Obras activas permitidas; nulo = ilimitadas") Integer maxActiveWorksites,
            boolean unlimitedWorksites, boolean trialOnly,
            @Schema(description = "Días de prueba gratuita (solo Free)") Integer trialDays,
            BigDecimal monthlyPrice, BigDecimal annualPrice, BigDecimal annualDiscountPercent, String currency) {

        static PlanResource of(Plan plan, int trialDays) {
            return new PlanResource(plan.code(), plan.name(), plan.description(), plan.maxActiveWorksites(),
                    plan.unlimited(), plan.trialOnly(), plan.trialOnly() ? trialDays : null, plan.monthlyPrice(),
                    plan.annualPrice(), plan.annualDiscountPercent(), plan.currency());
        }
    }
}
