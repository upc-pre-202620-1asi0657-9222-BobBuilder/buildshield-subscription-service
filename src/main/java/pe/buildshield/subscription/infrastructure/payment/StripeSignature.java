package pe.buildshield.subscription.infrastructure.payment;

import pe.buildshield.subscription.application.InvalidPaymentWebhookException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Firma de los webhooks de Stripe (cabecera {@code Stripe-Signature: t=<segundos>,v1=<hex>}): HMAC-SHA256 del texto
 * {@code <t>.<cuerpo>} con el secreto del endpoint ({@code whsec_...}). Se acepta si alguna firma {@code v1} coincide
 * (comparación de tiempo constante) y la marca de tiempo está dentro de la tolerancia, contra ataques de repetición.
 */
final class StripeSignature {

    private StripeSignature() {
    }

    /** Cabecera {@code Stripe-Signature} para ese cuerpo, como la arma Stripe. */
    static String header(String payload, String secret, Instant at) {
        long timestamp = at.getEpochSecond();
        return "t=" + timestamp + ",v1=" + hmac(timestamp + "." + payload, secret);
    }

    static void verify(String payload, String header, String secret, Duration tolerance, Instant now) {
        if (secret == null || secret.isBlank()) {
            throw new InvalidPaymentWebhookException("El webhook de pagos no está configurado (STRIPE_WEBHOOK_SECRET)");
        }
        if (payload == null || header == null || header.isBlank()) {
            throw new InvalidPaymentWebhookException("Falta la firma Stripe-Signature del webhook");
        }
        Long timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length != 2) {
                continue;
            }
            if (pair[0].equals("t")) {
                timestamp = parse(pair[1]);
            } else if (pair[0].equals("v1")) {
                signatures.add(pair[1]);
            }
        }
        if (timestamp == null || signatures.isEmpty()) {
            throw new InvalidPaymentWebhookException("La firma Stripe-Signature no tiene marca de tiempo o firma v1");
        }
        if (Duration.between(Instant.ofEpochSecond(timestamp), now).abs().compareTo(tolerance) > 0) {
            throw new InvalidPaymentWebhookException("La firma del webhook está fuera de la tolerancia de tiempo");
        }
        byte[] expected = hmac(timestamp + "." + payload, secret).getBytes(StandardCharsets.US_ASCII);
        for (String signature : signatures) {
            if (MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.US_ASCII))) {
                return;
            }
        }
        throw new InvalidPaymentWebhookException("La firma del webhook no es válida");
    }

    private static Long parse(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static String hmac(String text, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 no disponible", ex);
        }
    }
}
