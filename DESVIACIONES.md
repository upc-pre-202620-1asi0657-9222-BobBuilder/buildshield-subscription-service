# Desviaciones y Alineación frente a la Arquitectura v4

Fuente: `buildshield-presentacion-v4.pdf` (Diagrama Entidad-Relación pág. 19 y Modelo C4 Nivel 3 - Componentes del Contenedor de Suscripción pág. 28). Este documento detalla la concordancia del esquema relacional y arquitectura de componentes del servicio `buildshield-subscription-service` frente a la especificación oficial.

## Columnas Aceptadas y Ampliaciones del ER

El esquema relacional de la base de datos `subscription_db` se encuentra alineado con el ER oficial de la pág. 19, incorporando las siguientes columnas clave:

| Tabla | Columna | Tipo | Justificación de Arquitectura |
|---|---|---|---|
| `subscriptions` | `organization_id` | `uuid not null` | Identificador de la organización propietaria de la suscripción, garantizando aislamiento multi-inquilino. |
| `subscriptions` | `pending_billing_period` | `varchar(10) null` | Soporte para cambio programado de modalidad (US12). Permite consultar (`GET /subscriptions/current`) y aplicar la nueva modalidad (MENSUAL / ANUAL) en la siguiente renovación sin llamadas sincrónicas a la pasarela. |
| `subscriptions` | `gateway_subscription_id` | `varchar(60) null` | Identificador de suscripción en Stripe (`sub_...`). Utilizado en US12 para operar directamente sobre el schedule de suscripción (`GET /v1/subscriptions/{id}`) sin requerir búsqueda lenta por metadatos. |
| `subscriptions` | `created_at`, `created_by`, `updated_at`, `updated_by` | Auditoría | Trazabilidad y auditoría de cambios de estado y titularidad en las suscripciones. |
| `payments` | `status` | `varchar(20) not null` | Estado del cobro: `PENDING`, `COMPLETED`, `FAILED`. |
| `payments` | `failure_reason` | `varchar(120) null` | Motivo de rechazo o fallo reportado por la pasarela de pagos al procesar un intento de cobro. |
| `outbox_events` | `correlation_id` | `uuid null` | Identificador de correlación para observabilidad distribuida y trazabilidad de eventos del transactional outbox pattern. |
| `processed_events`| `consumer` | `varchar(60) not null` | Nombre del consumidor en tiempo de ejecución (`OrganizationEventConsumer`, `StripeWebhooks`) que garantiza procesamiento idempotente. |

## Componentes C4 (Nivel 3 - Suscripción y Pagos)

De acuerdo con el diagrama C4 de Componentes (pág. 28), se organizan los componentes principales:

1. **`SubscriptionController`** (`pe.buildshield.subscription.interfaces.rest`):
   - Controlador REST unificado que expone los endpoints de planes (`/api/v1/plans`), suscripción del inquilino actual (`/api/v1/subscriptions/current`), inicio de checkout (`/api/v1/subscriptions/checkout`), cambio de modalidad de facturación (`/api/v1/subscriptions/billing-period` y `/current/billing-period`) y finalización de sesión simulada (`/api/v1/subscriptions/simulated-sessions/{sessionId}/complete` y `/checkout/{sessionId}/simulated-payment`).
2. **`StripePaymentAdapter`** (`pe.buildshield.subscription.infrastructure.payment`):
   - Implementación del puerto `PaymentGateway` para la integración HTTP con Stripe (Checkout Sessions, Subscription Schedules).
   - Incorpora Circuit Breaker de Resilience4j (50 % fallos en 10 llamadas abre por 30 s), tiempo de espera configurable (`STRIPE_TIMEOUT`) y cabecera `Idempotency-Key` en mutaciones.
3. **`StripeWebhookController`** (`pe.buildshield.subscription.interfaces.rest`):
   - Endpoint dedicado para recibir notificaciones asíncronas de la pasarela en `/api/v1/payments/webhooks/stripe` y `/api/v1/subscriptions/webhooks/stripe`.
   - Valida criptográficamente la cabecera `Stripe-Signature` (HMAC-SHA256 con ventana de tolerancia de 5 minutos).
4. **`OrganizationEventConsumer`** (`pe.buildshield.subscription.infrastructure.messaging`):
   - Consumidor de eventos del broker Kafka para el tópico `buildshield.iam`.
   - Ingesta de forma idempotente `OrganizationRegistered` para registrar la suscripción inicial con prueba gratuita de 14 días (`TRIAL`).
5. **`Discount` (Agregado / Objeto de Valor de Dominio)** (`pe.buildshield.subscription.domain.model`):
   - Modelo de dominio puro (sin dependencias de frameworks) que encapsula el porcentaje de descuento anual (`annualDiscountPct`), la validación ($0 \le pct \le 100$) y el cálculo del precio anual redondeado y ahorro con respecto a la facturación mensual.

## Flujo de Cobro Fallido y Evento `PaymentFailed`

Alineado con los requisitos de negocio y `TAREA.md`:

1. **Recepción en `StripeWebhooks`:**
   - Soporte para el webhook `invoice.payment_failed` de Stripe.
   - Extrae el identificador de la organización, id de suscripción externa (`sub_...`), referencia de factura/cobro (`in_...`), monto facturado y motivo del rechazo (`failureReason` a partir de `last_finalization_error.message`, `billing_reason` o `"Payment failed"`).
2. **Procesamiento en `SubscriptionCommandService`:**
   - Se localiza el pago pendiente correspondiente o se registra un nuevo cobro en estado `FAILED`.
   - Se actualiza `payments.status = 'FAILED'`, `failure_reason` (truncado a 120 caracteres) y `gateway_charge_id`.
   - Se emite el evento de integración `PaymentFailed` a través de la tabla `outbox_events` hacia el tópico `buildshield.subscription`.
3. **Payload de Eventos de Pago y Suscripción:**
   - Todos los eventos de ciclo de vida (`SubscriptionActivated`, `SubscriptionExpired`, `PaymentSucceeded`, `PaymentFailed`) publican los campos canónicos requeridos: `organizationId`, `plan`, `status`, `maxActiveWorksites`, `periodEnd` (y en fallos, `failureReason`).
4. **Idempotencia:**
   - Los reintentos de checkout o webhook con la misma clave de idempotencia devuelven la sesión existente o son descartados mediante `processed_events`, previniendo cobros dobles o procesamiento redundante.

## Lo que el ER simplificó y su resolución en el Servicio

| Dato en esquema histórico | Solución en Arquitectura v4 | Efecto |
|---|---|---|
| `plans.name`, `description`, `trial_only`, `currency`, `sort_order` | Resuelto en código en el enum de dominio `PlanCode` y agregado `Plan` (moneda `PEN`). | Estabilidad de catálogo; la API REST expone nombres legibles y metadatos completos. |
| `plans.annual_price` | Calculado mediante el objeto de valor `Discount`: `monthly_price × 12 × (1 − annual_discount_pct / 100)`. | Reproduce con precisión los precios sembrados (S/ 2 990 y S/ 6 990 con 16,67 %). |
| `subscriptions.started_at` | Mapeado a `created_at`. | Inserción consistente con marcas temporales de auditoría. |
| `subscriptions.valid_until` | `trial_ends_at` durante el periodo `TRIAL` y `period_end` (a las 00:00 UTC) en planes pagados. | Precisión adecuada para corte de servicio y control de lectura con margen de cortesía de 3 días. |
| `payment_checkouts` | Unificado en tabla `payments`. Plan, modalidad y clave de idempotencia viajan firmados en los metadatos de la sesión de Stripe. | Eliminación de tablas redundantes conservando verificación criptográfica de webhooks. |
