/**
 * Eventos de integración entre microservicios: sobre JSON común ({@link pe.buildshield.subscription.shared.messaging.EventEnvelope}),
 * Transactional Outbox ({@link pe.buildshield.subscription.shared.messaging.OutboxWriter} y
 * {@link pe.buildshield.subscription.shared.messaging.OutboxPublisher}) y consumo idempotente
 * ({@link pe.buildshield.subscription.shared.messaging.IdempotentEventProcessor}). Entrega al menos una vez.
 */
package pe.buildshield.subscription.shared.messaging;
