/**
 * Microservicio Suscripción y Pagos (contexto genérico): planes Free, Plus y Pro, prueba gratuita al registrar la
 * organización y vencimiento (cuenta en solo lectura). Publica en {@code buildshield.subscription} y consume
 * {@code buildshield.iam}.
 *
 * <p>Capas: {@code interfaces} (REST) → {@code application} (casos de uso) → {@code domain} (Java puro);
 * {@code infrastructure} implementa los puertos (JPA, Kafka, pasarela de pagos). {@code shared} es el kernel
 * compartido copiado de buildshield-iam-service.
 */
package pe.buildshield.subscription;
