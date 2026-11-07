# buildshield-subscription-service

Suscripción y Pagos de BuildShield: catálogo de planes, prueba gratuita al registrar la organización,
pago de planes mediante Stripe Checkout (webhooks criptográficamente firmados), cambio de modalidad
(mensual / anual) y vencimiento de suscripciones con degradación a solo lectura. Alineado con la
arquitectura v4 (`buildshield-presentacion-v4.pdf`, ER pág. 19 y C4 Nivel 3 pág. 28).

| Dato | Valor |
|---|---|
| Puerto | 8086 |
| Base de datos | `subscription_db` (PostgreSQL 16+) |
| Paquete raíz | `pe.buildshield.subscription` |
| Tópico publicado | `buildshield.subscription`: `SubscriptionActivated`, `SubscriptionExpired`, `PaymentSucceeded`, `PaymentFailed` y `SubscriptionBillingPeriodChangeScheduled`. Payload canónico: `organizationId`, `plan`, `status`, `maxActiveWorksites`, `periodEnd` (y en fallos, `failureReason`) |
| Tópico consumido | `buildshield.iam`: `OrganizationRegistered` (mediante `OrganizationEventConsumer`) |

## Componentes de Arquitectura (C4 Nivel 3)

De acuerdo con el modelo C4 (pág. 28):

- **`SubscriptionController`** (`pe.buildshield.subscription.interfaces.rest`): Componente controlador REST unificado que expone endpoints para consulta del catálogo de planes, gestión de la suscripción actual del inquilino, inicio de sesiones de checkout y cambio programado de modalidad de facturación.
- **`StripePaymentAdapter`** (`pe.buildshield.subscription.infrastructure.payment`): Adaptador de infraestructura para la pasarela de pagos Stripe, protegido con Circuit Breaker de Resilience4j, reintentos idempotentes y control de tiempo de espera.
- **`StripeWebhookController`** (`pe.buildshield.subscription.interfaces.rest`): Controlador de webhook que recibe eventos asíncronos de cobro y suscripción, verificando la firma `Stripe-Signature` antes de delegar a la capa de aplicación.
- **`OrganizationEventConsumer`** (`pe.buildshield.subscription.infrastructure.messaging`): Consumidor Kafka para el registro de organizaciones desde IAM, aprovisionando de manera idempotente la suscripción de prueba.
- **`Discount` y `Plan`** (`pe.buildshield.subscription.domain.model`): Agregados y objetos de valor del dominio puro (sin dependencias de frameworks) para el cálculo de tarifas, descuentos anuales y políticas de límites de obras.

## Modelo de Ingresos y Planes

- **Free**: periodo de prueba gratuita por `SUBSCRIPTION_TRIAL_DAYS` días (14 por defecto) con acceso completo.
- **Plus**: hasta 5 obras activas (`maxActiveWorksites = 5`).
- **Pro**: obras activas ilimitadas (`maxActiveWorksites = null`).
- Modalidad mensual o anual: el plan anual incluye descuento del 16,67 % (equivalente a 10 meses), encapsulado en el objeto de valor de dominio `Discount`.
- Vencimiento: al expirar el periodo contratado o de prueba, la suscripción pasa al estado `EXPIRED`, publicando `SubscriptionExpired` para que IAM establezca la organización en modo solo lectura.
- Cobros y fallos: los cobros exitosos generan `PaymentSucceeded`; si un cobro de renovación o activación falla, se procesa `invoice.payment_failed`, se registra el motivo de rechazo en `payments.failure_reason` y se publica `PaymentFailed`.

## Endpoints REST

| Método y ruta | Autorización | Descripción |
|---|---|---|
| `GET /api/v1/plans` | Público | Catálogo de planes vigentes con precios mensual y anual, porcentaje de descuento y límites |
| `GET /api/v1/subscriptions/current` | Autenticado | Detalle de la suscripción del inquilino actual, estado, modalidad y vigencia |
| `POST /api/v1/subscriptions/checkout` | `ADMINISTRATOR` | Inicializa sesión de pago con Stripe Checkout devolviendo `sessionId` y `checkoutUrl` |
| `POST /api/v1/subscriptions/simulated-sessions/{sessionId}/complete` | `ADMINISTRATOR` | Completa el flujo de pago en ambiente de simulación local |
| `PUT /api/v1/subscriptions/billing-period` | `ADMINISTRATOR` | Programa el cambio de modalidad (mensual/anual) para el próximo periodo de facturación |
| `POST /api/v1/payments/webhooks/stripe` | Público (Firma) | Recepción y verificación de eventos Stripe (`checkout.session.completed`, `invoice.paid`, `invoice.payment_failed`) |

## Variables de Entorno

- Base de datos: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`.
- Mensajería y Seguridad: `KAFKA_BOOTSTRAP_SERVERS`, `BUILDSHIELD_JWT_PUBLIC_KEY`.
- Políticas de suscripción: `SUBSCRIPTION_TRIAL_DAYS`, `SUBSCRIPTION_EXPIRY_CHECK_INTERVAL`.
- Pasarela Stripe: `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `STRIPE_TIMEOUT`, `STRIPE_API_BASE_URL`, `PAYMENTS_SIMULATION_ENABLED`.

## Verificación y Pruebas

Para ejecutar las pruebas completas con el clúster PostgreSQL local:

```bash
.\mvnw.cmd verify "-Dbuildshield.test.external-database=true"
```

El conjunto de pruebas incluye:
- Pruebas unitarias de dominio (`DiscountTest`, `PlanTest`, `PaymentTest`).
- Pruebas de reglas de arquitectura con ArchUnit (`ModuleBoundariesTest`, `ArchitectureRules`).
- Pruebas de integración de adaptadores y webhooks (`StripePaymentAdapterTest`, `StripeWebhooksTest`, `SubscriptionCommandServiceTest`).
- Pruebas de integración de componentes (`*IT`) y escenarios BDD con Cucumber.
- Verificación de cobertura de código JaCoCo $\ge 80\%$.
