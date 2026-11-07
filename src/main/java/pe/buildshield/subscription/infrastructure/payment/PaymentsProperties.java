package pe.buildshield.subscription.infrastructure.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import pe.buildshield.subscription.domain.model.BillingPeriod;
import pe.buildshield.subscription.domain.model.PlanCode;

import java.time.Duration;

/**
 * Configuración de la pasarela de pagos ({@code buildshield.payments.*}). Las claves llegan solo por variables de
 * entorno ({@code STRIPE_SECRET_KEY}, {@code STRIPE_WEBHOOK_SECRET}): nunca se escriben en el repositorio.
 *
 * @param simulationEnabled    permite el modo simulado (solo el perfil local); se usa si además no hay clave de Stripe
 * @param successUrl           a dónde vuelve el cliente tras pagar ({@code {CHECKOUT_SESSION_ID}} lo completa Stripe)
 * @param cancelUrl            a dónde vuelve el cliente si cancela el pago
 * @param simulatedCheckoutUrl página de la web que simula el Checkout de Stripe en el modo simulado
 */
@ConfigurationProperties(prefix = "buildshield.payments")
public record PaymentsProperties(boolean simulationEnabled, String successUrl, String cancelUrl,
        String simulatedCheckoutUrl, Stripe stripe) {

    /** Secreto de firma usado en el modo simulado si no se configuró {@code STRIPE_WEBHOOK_SECRET}. Solo desarrollo. */
    public static final String SIMULATED_WEBHOOK_SECRET = "whsec_simulated_local_only";

    public PaymentsProperties {
        successUrl = blank(successUrl) ? "http://localhost:4200/suscripcion?pago=exitoso" : successUrl;
        cancelUrl = blank(cancelUrl) ? "http://localhost:4200/suscripcion?pago=cancelado" : cancelUrl;
        simulatedCheckoutUrl = blank(simulatedCheckoutUrl)
                ? "http://localhost:4200/suscripcion/pago-simulado" : simulatedCheckoutUrl;
        stripe = stripe == null ? new Stripe(null, null, null, null, null, null) : stripe;
    }

    /** Modo simulado: permitido por el perfil y sin clave de Stripe configurada. */
    public boolean simulated() {
        return simulationEnabled && blank(stripe.secretKey());
    }

    /** Secreto con el que se verifican (y en el modo simulado, se firman) los webhooks; nulo si falta. */
    public String effectiveWebhookSecret() {
        if (!blank(stripe.webhookSecret())) {
            return stripe.webhookSecret();
        }
        return simulated() ? SIMULATED_WEBHOOK_SECRET : null;
    }

    static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Stripe.
     *
     * @param secretKey        clave secreta de la API ({@code sk_test_...} o {@code sk_live_...})
     * @param webhookSecret    secreto de firma del endpoint de webhook ({@code whsec_...})
     * @param apiBaseUrl       URL base de la API (por defecto {@code https://api.stripe.com})
     * @param timeout          tiempo máximo de conexión y de respuesta de cada llamada
     * @param webhookTolerance antigüedad máxima aceptada de la marca de tiempo de la firma
     * @param prices           ids de precio de Stripe por plan y modalidad; si faltan se envía {@code price_data}
     */
    public record Stripe(String secretKey, String webhookSecret, String apiBaseUrl, Duration timeout,
            Duration webhookTolerance, Prices prices) {

        public Stripe {
            apiBaseUrl = blank(apiBaseUrl) ? "https://api.stripe.com" : trimSlash(apiBaseUrl);
            timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
            webhookTolerance = webhookTolerance == null ? Duration.ofMinutes(5) : webhookTolerance;
            prices = prices == null ? new Prices(null, null, null, null) : prices;
        }

        private static String trimSlash(String url) {
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
    }

    /** Ids de precio ({@code price_...}) configurados en el panel de Stripe. Vacíos: precio en línea del catálogo. */
    public record Prices(String plusMonthly, String plusAnnual, String proMonthly, String proAnnual) {

        /** El id configurado o nulo. */
        public String priceId(PlanCode plan, BillingPeriod period) {
            String id = switch (plan) {
                case PLUS -> period == BillingPeriod.ANNUAL ? plusAnnual : plusMonthly;
                case PRO -> period == BillingPeriod.ANNUAL ? proAnnual : proMonthly;
                case FREE -> null;
            };
            return blank(id) ? null : id;
        }
    }
}
