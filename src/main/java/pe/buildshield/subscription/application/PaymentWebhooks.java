package pe.buildshield.subscription.application;

import pe.buildshield.subscription.domain.model.PaymentCheckout;

import java.time.Instant;

/**
 * Puerto de las notificaciones (webhooks) de la pasarela. La autenticidad la da la firma: el adaptador de Stripe
 * verifica la cabecera {@code Stripe-Signature} (HMAC-SHA256 con tolerancia de tiempo) antes de entregar el evento.
 */
public interface PaymentWebhooks {

    /**
     * Verifica la firma y traduce el evento de la pasarela.
     *
     * @throws InvalidPaymentWebhookException si la firma no es válida, está vencida o el cuerpo no es un evento
     */
    PaymentEvent verify(String payload, String signatureHeader);

    /**
     * Modo simulado: arma y firma, con el mismo secreto del webhook, el evento {@code checkout.session.completed} que
     * enviaría Stripe al pagarse la sesión. Así el pago simulado recorre el mismo camino que el real.
     */
    SignedWebhook simulateCheckoutCompleted(PaymentCheckout checkout, Instant paidAt);

    /** Cuerpo del webhook y su cabecera {@code Stripe-Signature}. */
    record SignedWebhook(String payload, String signatureHeader) {
    }
}
