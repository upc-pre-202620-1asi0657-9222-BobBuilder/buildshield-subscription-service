package pe.buildshield.subscription.interfaces.rest;

/** Ejemplos de respuesta para la documentación OpenAPI de subscription-service. */
final class SubscriptionApiExamples {

    static final String PLANS = """
            [
              {
                "code": "FREE",
                "name": "Free",
                "description": "Prueba gratuita con todas las funciones",
                "maxActiveWorksites": null,
                "unlimitedWorksites": true,
                "trialOnly": true,
                "trialDays": 14,
                "monthlyPrice": 0.00,
                "annualPrice": 0.00,
                "annualDiscountPercent": 0.00,
                "currency": "PEN"
              },
              {
                "code": "PLUS",
                "name": "Plus",
                "description": "Hasta 5 obras activas, todas las funciones",
                "maxActiveWorksites": 5,
                "unlimitedWorksites": false,
                "trialOnly": false,
                "trialDays": null,
                "monthlyPrice": 299.00,
                "annualPrice": 2990.00,
                "annualDiscountPercent": 16.67,
                "currency": "PEN"
              },
              {
                "code": "PRO",
                "name": "Pro",
                "description": "Obras ilimitadas, todas las funciones",
                "maxActiveWorksites": null,
                "unlimitedWorksites": true,
                "trialOnly": false,
                "trialDays": null,
                "monthlyPrice": 699.00,
                "annualPrice": 6990.00,
                "annualDiscountPercent": 16.67,
                "currency": "PEN"
              }
            ]""";

    static final String CURRENT = """
            {
              "id": "7c1e2d3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f",
              "organizationId": "3f2a1b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b",
              "plan": "FREE",
              "planName": "Free",
              "maxActiveWorksites": null,
              "status": "TRIAL",
              "statusLabel": "En prueba gratuita",
              "billingPeriod": null,
              "startedAt": "2026-10-08T15:00:00Z",
              "validUntil": "2026-10-22T15:00:00Z",
              "daysRemaining": 14,
              "readOnly": false
            }""";

    private SubscriptionApiExamples() {
    }
}
