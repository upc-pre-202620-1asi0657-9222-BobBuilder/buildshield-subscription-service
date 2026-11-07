package pe.buildshield.subscription.application;

import pe.buildshield.subscription.shared.error.ValidationException;

/** Webhook rechazado: firma ausente, inválida o fuera de la tolerancia de tiempo, o cuerpo ilegible (HTTP 400). */
public class InvalidPaymentWebhookException extends ValidationException {

    public static final String CODE = "INVALID_WEBHOOK_SIGNATURE";

    public InvalidPaymentWebhookException(String message) {
        super(CODE, message);
    }
}
