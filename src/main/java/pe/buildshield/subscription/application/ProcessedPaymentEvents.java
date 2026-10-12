package pe.buildshield.subscription.application;

/**
 * Registro de los eventos de la pasarela ya procesados (deduplicación por id de evento): Stripe entrega cada webhook
 * al menos una vez y lo reintenta mientras no reciba 2xx.
 */
public interface ProcessedPaymentEvents {

    /**
     * Registra el evento dentro de la transacción en curso.
     *
     * @return {@code false} si ya estaba registrado (duplicado)
     */
    boolean markProcessed(String eventId, String eventType);
}
