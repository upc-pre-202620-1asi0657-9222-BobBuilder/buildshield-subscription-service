package pe.buildshield.subscription.domain.model;

/**
 * Planes del modelo de ingresos: Free (solo prueba), Plus (hasta 5 obras activas) y Pro (ilimitadas), en el orden en
 * que se muestran al cliente. El nombre y la descripción viven aquí: el ER solo guarda código, límite y precios.
 */
public enum PlanCode {
    FREE("Free", "Prueba gratuita con todas las funciones"),
    PLUS("Plus", "Hasta 5 obras activas, todas las funciones"),
    PRO("Pro", "Obras ilimitadas, todas las funciones");

    private final String displayName;
    private final String description;

    PlanCode(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }
}
