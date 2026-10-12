package pe.buildshield.subscription.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.buildshield.subscription.application.SubscriptionQueryService;
import pe.buildshield.subscription.domain.model.Plan;
import pe.buildshield.subscription.domain.model.PlanCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@RestController
@RequestMapping("/api/v1/plans")
@Tag(name = "Planes", description = "Catálogo de planes: Free (solo prueba), Plus (hasta 5 obras activas) y Pro (ilimitadas)")
class PlansController {

    private final SubscriptionQueryService queries;

    PlansController(SubscriptionQueryService queries) {
        this.queries = queries;
    }

    /** Público (sin token): lo usa la página de inicio para mostrar los precios (US08). */
    @GetMapping
    @Operation(summary = "Listar los planes (público)",
            description = "US08: planes con su límite de obras activas y su precio mensual y anual. El anual se "
                    + "compara con doce pagos mensuales: ahorro en soles y porcentaje de descuento. Todos los planes "
                    + "tienen las mismas funciones. No requiere sesión.")
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
            @Schema(description = "Precio de un mes", example = "299.00") BigDecimal monthlyPrice,
            @Schema(description = "Precio de un año", example = "2990.00") BigDecimal annualPrice,
            @Schema(description = "Lo que costarían doce pagos mensuales", example = "3588.00")
            BigDecimal twelveMonthsPrice,
            @Schema(description = "Ahorro del pago anual frente a doce mensuales", example = "598.00")
            BigDecimal annualSavings,
            @Schema(description = "Descuento del pago anual en porcentaje", example = "16.67")
            BigDecimal annualDiscountPercent,
            @Schema(description = "Precio anual dividido entre doce meses", example = "249.17")
            BigDecimal annualMonthlyEquivalent,
            String currency) {

        static PlanResource of(Plan plan, int trialDays) {
            return new PlanResource(plan.code(), plan.name(), plan.description(), plan.maxActiveWorksites(),
                    plan.unlimited(), plan.trialOnly(), plan.trialOnly() ? trialDays : null, plan.monthlyPrice(),
                    plan.annualPrice(), plan.twelveMonthsPrice(), plan.annualSavings(), plan.annualDiscountPercent(),
                    plan.annualPrice().divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP), plan.currency());
        }
    }
}
