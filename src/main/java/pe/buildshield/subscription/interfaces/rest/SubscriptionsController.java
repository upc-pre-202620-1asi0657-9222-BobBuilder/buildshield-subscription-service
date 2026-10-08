package pe.buildshield.subscription.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
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
import pe.buildshield.subscription.application.SubscriptionQueryService.SubscriptionView;
import pe.buildshield.subscription.domain.model.BillingPeriod;
import pe.buildshield.subscription.domain.model.PlanCode;
import pe.buildshield.subscription.domain.model.SubscriptionStatus;
import pe.buildshield.subscription.shared.error.ErrorResponse;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/subscriptions")
@Tag(name = "Suscripciones", description = "US09: estado de la suscripción de la organización")
@SecurityRequirement(name = "bearer")
class SubscriptionsController {

    private final SubscriptionQueryService queries;

    SubscriptionsController(SubscriptionQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/current")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Consultar la suscripción de mi organización",
            description = "Plan, estado (prueba, activa o vencida), fecha de fin y días restantes. Con la "
                    + "suscripción vencida la cuenta queda en solo lectura (readOnly = true).")
    @ApiResponse(responseCode = "200", description = "Suscripción de la organización del token",
            content = @Content(schema = @Schema(implementation = SubscriptionResource.class),
                    examples = @ExampleObject(value = SubscriptionApiExamples.CURRENT)))
    @ApiResponse(responseCode = "404", description = "La organización todavía no tiene suscripción",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    SubscriptionResource current() {
        return SubscriptionResource.of(queries.current());
    }

    @Schema(description = "Suscripción de la organización")
    record SubscriptionResource(UUID id, UUID organizationId, PlanCode plan, String planName,
            Integer maxActiveWorksites, SubscriptionStatus status, String statusLabel, BillingPeriod billingPeriod,
            Instant startedAt, Instant validUntil, long daysRemaining,
            @Schema(description = "true: cuenta en solo lectura hasta pagar") boolean readOnly) {

        static SubscriptionResource of(SubscriptionView view) {
            return new SubscriptionResource(view.id(), view.organizationId(), view.plan().code(), view.plan().name(),
                    view.plan().maxActiveWorksites(), view.status(), view.status().label(), view.billingPeriod(),
                    view.startedAt(), view.validUntil(), view.daysRemaining(), view.readOnly());
        }
    }
}
