package pe.buildshield.subscription.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.buildshield.subscription.application.SubscriptionCommandService;
import pe.buildshield.subscription.application.SubscriptionCommandService.CheckoutStarted;
import pe.buildshield.subscription.application.SubscriptionQueryService;
import pe.buildshield.subscription.application.SubscriptionQueryService.SubscriptionView;
import pe.buildshield.subscription.domain.model.BillingPeriod;
import pe.buildshield.subscription.domain.model.CheckoutStatus;
import pe.buildshield.subscription.domain.model.PaymentProvider;
import pe.buildshield.subscription.domain.model.PlanCode;
import pe.buildshield.subscription.domain.model.Subscription.BillingPeriodChange;
import pe.buildshield.subscription.domain.model.SubscriptionStatus;
import pe.buildshield.subscription.shared.error.ErrorResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/subscriptions")
@Tag(name = "Suscripciones", description = "US07, US09, US11 y US12: suscripción de la organización, pago con Stripe "
        + "Checkout y cambio de modalidad")
@SecurityRequirement(name = "bearer")
class SubscriptionsController {

    private final SubscriptionQueryService queries;
    private final SubscriptionCommandService commands;

    SubscriptionsController(SubscriptionQueryService queries, SubscriptionCommandService commands) {
        this.queries = queries;
        this.commands = commands;
    }

    @GetMapping("/current")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Consultar la suscripción de mi organización",
            description = "US11: plan, modalidad (mensual o anual), estado (prueba, activa o vencida), inicio del "
                    + "periodo, próxima fecha de cobro, cambio de modalidad programado y días restantes. Con la "
                    + "suscripción vencida la cuenta queda en solo lectura (readOnly = true).")
    @ApiResponse(responseCode = "200", description = "Suscripción de la organización del token",
            content = @Content(schema = @Schema(implementation = SubscriptionResource.class),
                    examples = {@ExampleObject(name = "Prueba gratuita", value = SubscriptionApiExamples.CURRENT),
                            @ExampleObject(name = "Plus anual pagado", value = SubscriptionApiExamples.CURRENT_ACTIVE)}))
    @ApiResponse(responseCode = "404", description = "La organización todavía no tiene suscripción",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    SubscriptionResource current() {
        return SubscriptionResource.of(queries.current());
    }

    @PostMapping("/checkout")
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Operation(summary = "Pagar un plan (abrir Stripe Checkout)",
            description = "US07 y US09: abre la sesión de pago del plan Plus o Pro en modalidad mensual o anual y "
                    + "devuelve la URL de Stripe Checkout a la que se redirige al administrador. La tarjeta se "
                    + "ingresa en Stripe: BuildShield nunca la recibe. La suscripción se activa cuando Stripe "
                    + "confirma el pago por webhook. Sin clave de Stripe, en desarrollo local, la URL lleva a la "
                    + "página de pago simulado de la web (simulated = true). Una sesión abierta reciente del mismo "
                    + "plan y modalidad se reutiliza.")
    @ApiResponse(responseCode = "201", description = "Sesión de pago abierta",
            content = @Content(schema = @Schema(implementation = CheckoutResource.class),
                    examples = @ExampleObject(value = SubscriptionApiExamples.CHECKOUT)))
    @ApiResponse(responseCode = "400", description = "Plan Free (no se paga) o datos inválidos",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Solo el administrador paga la suscripción",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "La organización ya tiene un plan pagado y vigente",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "La pasarela de pagos no responde o no está configurada",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    ResponseEntity<CheckoutResource> checkout(@Valid @RequestBody CheckoutRequest request) {
        CheckoutStarted started = commands.startCheckout(request.plan(), request.billingPeriod());
        return ResponseEntity.status(HttpStatus.CREATED).body(CheckoutResource.of(started));
    }

    @PostMapping("/checkout/{sessionId}/simulated-payment")
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Operation(summary = "Completar un pago simulado (solo desarrollo local)",
            description = "Modo simulado, sin cuenta de Stripe: completa el pago de la sesión. El servicio arma y "
                    + "firma el mismo evento checkout.session.completed que enviaría Stripe y lo procesa por el "
                    + "camino del webhook. Con Stripe configurado responde 409.")
    @ApiResponse(responseCode = "200", description = "Pago simulado aplicado: suscripción activa",
            content = @Content(schema = @Schema(implementation = SubscriptionResource.class),
                    examples = @ExampleObject(value = SubscriptionApiExamples.CURRENT_ACTIVE)))
    @ApiResponse(responseCode = "403", description = "Solo el administrador paga la suscripción",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "La sesión de pago no existe o es de otra organización",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "El pago simulado está desactivado (Stripe configurado)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    SubscriptionResource simulatedPayment(@Parameter(description = "Sesión de pago simulada",
            example = "cs_sim_9f1c2d3e4a5b4c6d8e7f9a0b1c2d3e4f") @PathVariable String sessionId) {
        commands.completeSimulatedPayment(sessionId);
        return SubscriptionResource.of(queries.current());
    }

    @PutMapping("/current/billing-period")
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @Operation(summary = "Cambiar entre mensual y anual",
            description = "US12: programa la nueva modalidad para el siguiente periodo de cobro (sin cobro ni "
                    + "prorrateo inmediato). Pedir la modalidad actual cancela un cambio programado. Con Stripe, el "
                    + "cambio se programa también en Stripe (subscription schedule).")
    @ApiResponse(responseCode = "200", description = "Cambio programado, cancelado o sin cambios",
            content = @Content(schema = @Schema(implementation = BillingPeriodChangeResource.class),
                    examples = @ExampleObject(value = SubscriptionApiExamples.BILLING_PERIOD_CHANGE)))
    @ApiResponse(responseCode = "400", description = "Modalidad inválida",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Solo el administrador cambia la modalidad",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "La suscripción no está pagada y vigente",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "Stripe no responde",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    BillingPeriodChangeResource changeBillingPeriod(@Valid @RequestBody BillingPeriodRequest request) {
        BillingPeriodChange outcome = commands.changeBillingPeriod(request.billingPeriod());
        return new BillingPeriodChangeResource(outcome, SubscriptionResource.of(queries.current()));
    }

    @Schema(description = "Plan y modalidad a pagar")
    record CheckoutRequest(
            @Schema(description = "PLUS o PRO (FREE es solo la prueba)", example = "PLUS") @NotNull PlanCode plan,
            @Schema(description = "MONTHLY o ANNUAL", example = "ANNUAL") @NotNull BillingPeriod billingPeriod) {
    }

    @Schema(description = "Nueva modalidad")
    record BillingPeriodRequest(
            @Schema(description = "MONTHLY o ANNUAL", example = "MONTHLY") @NotNull BillingPeriod billingPeriod) {
    }

    @Schema(description = "Sesión de pago")
    record CheckoutResource(String sessionId,
            @Schema(description = "URL de Stripe Checkout (o de la página de pago simulado) a la que redirigir")
            String checkoutUrl, PlanCode plan, BillingPeriod billingPeriod,
            @Schema(description = "Monto del primer cobro", example = "2990.00") BigDecimal amount, String currency,
            PaymentProvider provider,
            @Schema(description = "true en el modo simulado de desarrollo local") boolean simulated,
            @Schema(description = "true si se reutilizó una sesión abierta reciente") boolean reused,
            CheckoutStatus status) {

        static CheckoutResource of(CheckoutStarted started) {
            var checkout = started.checkout();
            return new CheckoutResource(checkout.sessionId(), checkout.checkoutUrl(), checkout.plan(),
                    checkout.billingPeriod(), started.plan().price(checkout.billingPeriod()), started.plan().currency(),
                    checkout.provider(), checkout.provider() == PaymentProvider.SIMULATED, started.reused(),
                    checkout.status());
        }
    }

    @Schema(description = "Resultado del cambio de modalidad")
    record BillingPeriodChangeResource(
            @Schema(description = "SCHEDULED (programado), CANCELLED (se canceló el programado) o UNCHANGED")
            BillingPeriodChange outcome, SubscriptionResource subscription) {
    }

    @Schema(description = "Suscripción de la organización")
    record SubscriptionResource(UUID id, UUID organizationId, PlanCode plan, String planName,
            Integer maxActiveWorksites, SubscriptionStatus status, String statusLabel,
            @Schema(description = "MONTHLY o ANNUAL; nulo en la prueba gratuita") BillingPeriod billingPeriod,
            @Schema(description = "Mensual o Anual") String billingPeriodLabel,
            @Schema(description = "Alta de la suscripción (inicio de la prueba)") Instant startedAt,
            @Schema(description = "Inicio del periodo actual (prueba o último cobro)") Instant currentPeriodStart,
            @Schema(description = "Fin del periodo actual") Instant validUntil,
            @Schema(description = "Próxima fecha de cobro; nula en la prueba o si venció") Instant nextBillingAt,
            @Schema(description = "Modalidad programada para el siguiente periodo") BillingPeriod pendingBillingPeriod,
            @Schema(description = "Desde cuándo rige la modalidad programada") Instant pendingChangeAt,
            @Schema(description = "STRIPE, SIMULATED o nulo en la prueba") PaymentProvider paymentProvider,
            long daysRemaining,
            @Schema(description = "true: cuenta en solo lectura hasta pagar") boolean readOnly) {

        static SubscriptionResource of(SubscriptionView view) {
            return new SubscriptionResource(view.id(), view.organizationId(), view.plan().code(), view.plan().name(),
                    view.plan().maxActiveWorksites(), view.status(), view.status().label(), view.billingPeriod(),
                    view.billingPeriod() == null ? null : view.billingPeriod().label(), view.startedAt(),
                    view.currentPeriodStart(), view.validUntil(), view.nextBillingAt(), view.pendingBillingPeriod(),
                    view.pendingChangeAt(), view.paymentProvider(), view.daysRemaining(), view.readOnly());
        }
    }
}
