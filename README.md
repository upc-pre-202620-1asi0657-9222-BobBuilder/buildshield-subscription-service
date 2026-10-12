# buildshield-subscription-service

Suscripción y Pagos de BuildShield (BobBuilder): catálogo de planes, prueba gratuita al registrar la organización,
pago del plan con Stripe Checkout (webhook firmado), cambio entre mensual y anual y vencimiento de la suscripción
(cuenta en solo lectura). Contrato de la arquitectura:
`wiki/informe-tf/plan-microservicios.md` del proyecto.

| Dato | Valor |
|---|---|
| Puerto | 8086 |
| Base | `subscription_db` (PostgreSQL 16+) |
| Paquete | `pe.buildshield.subscription` (kernel compartido copiado de iam en `pe.buildshield.subscription.shared`) |
| Publica | `buildshield.subscription`: `SubscriptionActivated` (prueba, pago confirmado o renovación), `SubscriptionExpired` (al vencer) y `SubscriptionBillingPeriodChangeScheduled` (cambio de modalidad programado o cancelado). Payload: `organizationId`, `subscriptionId`, `plan` (FREE, PLUS, PRO), `status` (TRIAL, ACTIVE, EXPIRED), `validUntil`, `billingPeriod`, `startedAt`, `maxActiveWorksites` (nulo = ilimitadas), `currentPeriodStart`, `nextBillingAt`, `pendingBillingPeriod`, `pendingChangeAt`, `paymentProvider` (STRIPE, SIMULATED) y `reason` (TRIAL_STARTED, PAYMENT_CONFIRMED, RENEWED, EXPIRED, CHANGE_SCHEDULED, CHANGE_CANCELLED) |
| Consume | `buildshield.iam`: `OrganizationRegistered` (inicia la prueba gratuita de 14 días, una sola vez por organización) |

## Modelo de ingresos

- **Free**: solo prueba gratuita, con todas las funciones, por `SUBSCRIPTION_TRIAL_DAYS` días (14 por defecto).
- **Plus**: hasta 5 obras activas. **Pro**: obras ilimitadas. Mismas funciones en todos los planes.
- Mensual o anual; el anual equivale a 10 meses (16,67 % de descuento). Precios sembrados por Flyway en soles
  (Plus S/ 299 al mes o S/ 2 990 al año, Pro S/ 699 o S/ 6 990): son supuestos de diseño, se cambian en `plans`.
- Al vencer, la suscripción pasa a `EXPIRED` y se publica `SubscriptionExpired`; iam deja la cuenta en solo lectura.
  El vencimiento lo revisa un proceso periódico (`SUBSCRIPTION_EXPIRY_CHECK_INTERVAL`, un minuto por defecto).
- Pagos (Sprint 4): `PaymentGateway` es el puerto de la pasarela y `StripeAdapter` su adaptador con la API HTTP de
  Stripe (Checkout en modo `subscription`, `Idempotency-Key`, tiempo máximo `STRIPE_TIMEOUT` y circuit breaker de
  Resilience4j: 50 % de fallas en 10 llamadas lo abre 30 s). El servicio nunca recibe ni guarda datos de tarjeta:
  solo el id de la sesión (`cs_...`), del cliente (`cus_...`) y de la suscripción de Stripe (`sub_...`).
- El webhook verifica `Stripe-Signature` (HMAC-SHA256 de `t.cuerpo`, tolerancia de 5 minutos), descarta los eventos
  repetidos por su id (`payment_webhook_events`) y activa la suscripción con el plan y la modalidad de la sesión
  guardada. `invoice.paid` de una renovación empieza el siguiente periodo y aplica el cambio de modalidad programado.
  Con Stripe, si el cobro de la renovación se demora, la cuenta sigue operativa 3 días antes de vencer.
- Cambio de modalidad (US12): se programa para el siguiente periodo de cobro (en Stripe, con un subscription schedule
  de dos fases, sin prorrateo). Pedir la modalidad actual cancela el cambio programado.
- **Modo simulado** (solo el perfil `local`, sin `STRIPE_SECRET_KEY`): la URL de checkout lleva a la página
  `/suscripcion/pago-simulado` de la web y el pago se completa con
  `POST /api/v1/subscriptions/checkout/{sessionId}/simulated-payment`, que arma y firma el mismo evento de Stripe y lo
  procesa por el camino del webhook. La renovación es automática al llegar la fecha de cobro. Secreto de firma por
  defecto en este modo: `whsec_simulated_local_only` (no es una clave real).

## Endpoints

| Método y ruta | Rol | Qué hace |
|---|---|---|
| `GET /api/v1/plans` | público | Planes con límite de obras, precio mensual y anual, `twelveMonthsPrice`, `annualSavings`, `annualDiscountPercent` y `annualMonthlyEquivalent` (US08) |
| `GET /api/v1/subscriptions/current` | cualquiera | Plan, modalidad, estado, `currentPeriodStart`, `nextBillingAt`, cambio programado, días restantes y `readOnly` (US11); 404 sin suscripción |
| `POST /api/v1/subscriptions/checkout` | ADMINISTRATOR | `{plan, billingPeriod}` → 201 con `sessionId` y `checkoutUrl` de Stripe Checkout (US07, US09); 409 si ya hay un plan pagado vigente; 503 si Stripe no responde |
| `POST /api/v1/subscriptions/checkout/{sessionId}/simulated-payment` | ADMINISTRATOR | Solo modo simulado: completa el pago; 409 con Stripe configurado |
| `PUT /api/v1/subscriptions/current/billing-period` | ADMINISTRATOR | `{billingPeriod}` → `SCHEDULED`, `CANCELLED` o `UNCHANGED` para el siguiente periodo (US12) |
| `POST /api/v1/subscriptions/webhooks/stripe` | público (firma) | Webhook de Stripe: `checkout.session.completed`, `checkout.session.async_payment_succeeded` e `invoice.paid` |
- OpenAPI en `/api/v1/api-docs` y `/swagger-ui.html` (perfiles local y test).

## Variables de entorno

`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS` (por defecto `localhost:9092`),
`BUILDSHIELD_JWT_PUBLIC_KEY`, `SUBSCRIPTION_TRIAL_DAYS` y `SUBSCRIPTION_EXPIRY_CHECK_INTERVAL`. Perfiles `local`,
`test` y `prod`.

Pagos (nunca escribir claves en el repositorio):

| Variable | Uso |
|---|---|
| `STRIPE_SECRET_KEY` | Clave secreta de Stripe (`sk_test_...` para pruebas). Vacía en `local`: modo simulado |
| `STRIPE_WEBHOOK_SECRET` | Secreto de firma del endpoint de webhook (`whsec_...`) |
| `STRIPE_PRICE_PLUS_MONTHLY`, `STRIPE_PRICE_PLUS_ANNUAL`, `STRIPE_PRICE_PRO_MONTHLY`, `STRIPE_PRICE_PRO_ANNUAL` | Ids de precio (`price_...`); si faltan se envía el precio del catálogo en línea (`price_data`, PEN) |
| `PAYMENTS_SUCCESS_URL`, `PAYMENTS_CANCEL_URL` | Retorno desde Stripe Checkout (por defecto `http://localhost:4200/suscripcion?...`) |
| `PAYMENTS_SIMULATED_CHECKOUT_URL` | Página de pago simulado de la web (por defecto `http://localhost:4200/suscripcion/pago-simulado`) |
| `PAYMENTS_SIMULATION_ENABLED` | Solo `local` (por defecto `true`): permite el modo simulado si no hay clave |
| `STRIPE_TIMEOUT`, `STRIPE_WEBHOOK_TOLERANCE`, `STRIPE_API_BASE_URL` | `PT5S`, `PT5M` y `https://api.stripe.com` |

Para probar con Stripe en modo test: `stripe listen --forward-to localhost:8080/api/v1/subscriptions/webhooks/stripe`
entrega los webhooks a través del gateway y muestra el `whsec_...` a usar como `STRIPE_WEBHOOK_SECRET`.

## Ejecutar en local (clúster PostgreSQL 127.0.0.1:5433 con autenticación trust)

```bash
mvn -B package -DskipTests
BUILDSHIELD_JWT_PUBLIC_KEY="$(cat ~/.buildshield/jwt-public.pem)" \
java -jar target/buildshield-subscription-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
```

## Pruebas

```bash
mvn -B verify -Dbuildshield.test.external-database=true
```

Las `*IT` usan la base temporal `subscription_it` en `127.0.0.1:5433` con el usuario `buildshield_core` y Kafka
embebido (`@EmbeddedKafka`). Sin la propiedad usan Testcontainers (requiere Docker). Cucumber cubre US08 (prueba
gratuita y planes públicos), US09 (estado), US07 y US09 del Sprint 4 (pago con webhook firmado, repetido, falso y
simulado) y US12 (cambio de modalidad). El adaptador de Stripe se prueba contra un Stripe simulado (servidor HTTP). JaCoCo exige 80 % de líneas en `domain` y `application`.
