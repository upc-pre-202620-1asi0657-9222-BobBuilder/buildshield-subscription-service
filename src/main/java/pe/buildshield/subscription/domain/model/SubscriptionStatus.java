package pe.buildshield.subscription.domain.model;

/** Estado de la suscripción. Con {@link #EXPIRED} la cuenta queda en solo lectura hasta pagar. */
public enum SubscriptionStatus {
    TRIAL("En prueba gratuita"),
    ACTIVE("Activa"),
    EXPIRED("Vencida");

    private final String label;

    SubscriptionStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
