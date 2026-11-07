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
                "twelveMonthsPrice": 0.00,
                "annualSavings": 0.00,
                "annualDiscountPercent": 0.00,
                "annualMonthlyEquivalent": 0.00,
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
                "twelveMonthsPrice": 3588.00,
                "annualSavings": 598.00,
                "annualDiscountPercent": 16.67,
                "annualMonthlyEquivalent": 249.17,
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
                "twelveMonthsPrice": 8388.00,
                "annualSavings": 1398.00,
                "annualDiscountPercent": 16.67,
                "annualMonthlyEquivalent": 582.50,
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
              "billingPeriodLabel": null,
              "startedAt": "2026-10-08T15:00:00Z",
              "currentPeriodStart": "2026-10-08T15:00:00Z",
              "validUntil": "2026-10-22T15:00:00Z",
              "nextBillingAt": null,
              "pendingBillingPeriod": null,
              "pendingChangeAt": null,
              "paymentProvider": null,
              "daysRemaining": 14,
              "readOnly": false
            }""";

    static final String CURRENT_ACTIVE = """
            {
              "id": "7c1e2d3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f",
              "organizationId": "3f2a1b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b",
              "plan": "PLUS",
              "planName": "Plus",
              "maxActiveWorksites": 5,
              "status": "ACTIVE",
              "statusLabel": "Activa",
              "billingPeriod": "ANNUAL",
              "billingPeriodLabel": "Anual",
              "startedAt": "2026-10-08T15:00:00Z",
              "currentPeriodStart": "2026-10-11T18:30:00Z",
              "validUntil": "2027-10-11T18:30:00Z",
              "nextBillingAt": "2027-10-11T18:30:00Z",
              "pendingBillingPeriod": null,
              "pendingChangeAt": null,
              "paymentProvider": "STRIPE",
              "daysRemaining": 365,
              "readOnly": false
            }""";

    static final String CHECKOUT = """
            {
              "sessionId": "cs_test_a1B2c3D4e5F6g7H8i9J0",
              "checkoutUrl": "https://checkout.stripe.com/c/pay/cs_test_a1B2c3D4e5F6g7H8i9J0",
              "plan": "PLUS",
              "billingPeriod": "ANNUAL",
              "amount": 2990.00,
              "currency": "PEN",
              "provider": "STRIPE",
              "simulated": false,
              "reused": false,
              "status": "OPEN"
            }""";

    static final String BILLING_PERIOD_CHANGE = """
            {
              "outcome": "SCHEDULED",
              "subscription": {
                "plan": "PLUS",
                "status": "ACTIVE",
                "billingPeriod": "ANNUAL",
                "billingPeriodLabel": "Anual",
                "currentPeriodStart": "2026-10-11T18:30:00Z",
                "validUntil": "2027-10-11T18:30:00Z",
                "nextBillingAt": "2027-10-11T18:30:00Z",
                "pendingBillingPeriod": "MONTHLY",
                "pendingChangeAt": "2027-10-11T18:30:00Z",
                "readOnly": false
              }
            }""";

    static final String WEBHOOK_RECEIPT = """
            {
              "received": true,
              "result": "PROCESSED"
            }""";

    private SubscriptionApiExamples() {
    }
}
