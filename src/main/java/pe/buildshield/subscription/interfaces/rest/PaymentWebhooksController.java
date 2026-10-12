package pe.buildshield.subscription.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.buildshield.subscription.application.SubscriptionCommandService;
import pe.buildshield.subscription.application.SubscriptionCommandService.WebhookResult;
import pe.buildshield.subscription.shared.error.ErrorResponse;

/**
 * Webhook de Stripe. Es público (sin JWT): la autenticidad la da la firma {@code Stripe-Signature}. El cuerpo se lee
 * tal cual llega (texto), porque la firma se calcula sobre los bytes exactos.
 */
@RestController
@RequestMapping("/api/v1/subscriptions/webhooks")
@Tag(name = "Webhooks de pago", description = "T74: notificaciones firmadas de Stripe")
class PaymentWebhooksController {

    private final SubscriptionCommandService commands;

    PaymentWebhooksController(SubscriptionCommandService commands) {
        this.commands = commands;
    }

    @PostMapping("/stripe")
    @Operation(summary = "Recibir un webhook de Stripe (público, firmado)",
            description = "Verifica Stripe-Signature (HMAC-SHA256 de \"t.cuerpo\" con STRIPE_WEBHOOK_SECRET y "
                    + "tolerancia de 5 minutos), descarta los eventos repetidos por su id y aplica "
                    + "checkout.session.completed (activa la suscripción) e invoice.paid de renovación (siguiente "
                    + "periodo). Los demás eventos se registran y se ignoran con 200.")
    @ApiResponse(responseCode = "200", description = "Evento recibido",
            content = @Content(schema = @Schema(implementation = WebhookReceipt.class),
                    examples = @ExampleObject(value = SubscriptionApiExamples.WEBHOOK_RECEIPT)))
    @ApiResponse(responseCode = "400", description = "Firma ausente, inválida o vencida, o cuerpo ilegible",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    WebhookReceipt stripe(@RequestBody String payload,
            @Parameter(description = "Firma de Stripe: t=<segundos>,v1=<hmac hex>",
                    example = "t=1760220000,v1=5257a869e7ecebeda32affa62cdca3fa51cad7e77a0e56ff536d0ce8e108d8bd")
            @RequestHeader(name = "Stripe-Signature", required = false) String signature) {
        return new WebhookReceipt(true, commands.receiveWebhook(payload, signature));
    }

    @Schema(description = "Acuse de recibo del webhook")
    record WebhookReceipt(boolean received,
            @Schema(description = "PROCESSED, DUPLICATE (ya procesado) o IGNORED (sin efecto)") WebhookResult result) {
    }
}
