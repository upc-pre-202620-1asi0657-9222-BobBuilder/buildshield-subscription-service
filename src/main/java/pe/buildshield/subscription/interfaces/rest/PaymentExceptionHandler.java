package pe.buildshield.subscription.interfaces.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import pe.buildshield.subscription.application.PaymentGateway;
import pe.buildshield.subscription.shared.error.ErrorResponse;

/**
 * La pasarela de pagos no disponible se responde con 503 y {@code Retry-After}, con el formato único de error. Va
 * antes que el manejador general del kernel compartido (que no se modifica: es igual en todos los servicios).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class PaymentExceptionHandler {

    static final String PAYMENT_GATEWAY_UNAVAILABLE = "PAYMENT_GATEWAY_UNAVAILABLE";

    private static final Logger log = LoggerFactory.getLogger(PaymentExceptionHandler.class);

    @ExceptionHandler(PaymentGateway.UnavailableException.class)
    ResponseEntity<ErrorResponse> unavailable(PaymentGateway.UnavailableException ex) {
        log.warn("Pasarela de pagos no disponible: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "30")
                .body(ErrorResponse.of(PAYMENT_GATEWAY_UNAVAILABLE, ex.getMessage()));
    }
}
