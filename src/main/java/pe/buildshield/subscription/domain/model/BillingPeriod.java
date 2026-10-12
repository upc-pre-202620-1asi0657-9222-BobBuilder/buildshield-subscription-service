package pe.buildshield.subscription.domain.model;

import java.time.Instant;
import java.time.ZoneOffset;

/** Modalidad de pago: mensual o anual (anual con descuento). La prueba gratuita no tiene modalidad. */
public enum BillingPeriod {
    MONTHLY("Mensual", 1),
    ANNUAL("Anual", 12);

    private final String label;
    private final int months;

    BillingPeriod(String label, int months) {
        this.label = label;
        this.months = months;
    }

    public String label() {
        return label;
    }

    /** Fin del periodo de cobro que empieza en {@code start} (mismo día del mes siguiente o del año siguiente, UTC). */
    public Instant periodEnd(Instant start) {
        return start.atZone(ZoneOffset.UTC).plusMonths(months).toInstant();
    }
}
