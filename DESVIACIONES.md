# Desviaciones frente al ER

Fuente: sección `subscription_db` de `wiki/informe-avance2/er-corregido.md`. Este archivo lista lo que el servicio tiene y el ER no dibuja, y cómo se resolvió lo que el ER quitó.

## Columnas fuera del ER

| Tabla | Columna | Motivo |
|---|---|---|
| `subscriptions` | `pending_billing_period` | US12 pide mostrar el cambio de modalidad programado y aplicarlo al renovar. `GET /subscriptions/current` y los eventos lo exponen en cada lectura. En Stripe vive en el subscription schedule, pero leerlo ahí haría depender cada consulta de Stripe, y el modo simulado no tiene dónde guardarlo. Es la única columna que sobra: **hay que agregarla al ER o decidir quitar US12**. |

`outbox_events.correlation_id` y la auditoría de `subscriptions` sí están en el ER.

## Lo que el ER quitó y cómo se resolvió

| Dato quitado | Solución | Efecto |
|---|---|---|
| `plans.name`, `description`, `trial_only`, `currency`, `sort_order` | En código: `PlanCode` y `Plan` (`PEN`). | Un cambio de nombre, descripción o moneda requiere desplegar. La API no cambia. |
| `plans.annual_price` | Se calcula: `monthly_price × 12 × (1 − annual_discount_pct / 100)`, redondeado al sol. | Reproduce S/ 2 990 y S/ 6 990 con 16,67 %. Con dos decimales de descuento no se puede fijar un precio anual con céntimos. |
| `subscriptions.plan_code` | `plan_id`. | Ninguno. |
| `subscriptions.started_at` | `created_at`. La entidad fija `created_at` con el inicio de la prueba al insertar, y V3 copia `started_at`. | `created_at` puede ser unos segundos anterior a la inserción real. |
| `subscriptions.valid_until` | `trial_ends_at` en la prueba y `period_end` en los planes pagados. `validUntil` de la API y de los eventos es `trial_ends_at` o las 00:00 UTC de `period_end`. | **`period_end` es una fecha: se pierde la hora.** Un pago de las 15:00 vence a las 00:00 de ese día, y el margen de cobro de 3 días lo cubre. |
| `subscriptions.current_period_start` | Se deriva: una modalidad antes de `period_end`. | Precisión de fecha. A fin de mes puede diferir: un pago del 31 de enero termina el 28 de febrero y se muestra con inicio el 28 de enero. |
| `subscriptions.expired_at` | Sin reemplazo. Ni la API ni los eventos lo exponían. | **Se pierde el dato** en V3. `updated_at` da una aproximación. |
| `subscriptions.payment_provider` | El proveedor es el de la pasarela configurada (`PaymentGateway.provider()`), y solo se informa en planes pagados. | El margen de cobro de 3 días ahora aplica a todo plan pagado; el modo simulado renueva antes de llegar a usarlo. Una base local con pagos simulados que luego arranca con clave de Stripe los trata como de Stripe. |
| `subscriptions.payment_customer_id` | Sin reemplazo: ningún flujo lo usaba. Sigue en Stripe. | **Se pierde el dato local** en V3. |
| `subscriptions.payment_subscription_id` | Metadatos de Stripe. `invoice.paid` trae `organizationId` en `subscription_details.metadata` (o `parent.subscription_details.metadata`). US12 ubica la suscripción con `GET /v1/subscriptions/search` por `metadata['organizationId']`. | La búsqueda de Stripe puede demorar hasta un minuto y agrega una llamada al cambiar de modalidad. No está disponible en todas las regiones de Stripe: **hay que confirmarlo con la cuenta real**. Sin credenciales, se probó solo contra un Stripe simulado. |
| `payment_checkouts` | `payments`. El plan, la modalidad y la clave del cobro viajan en los metadatos de la sesión, protegidos por la firma del webhook. | La activación ya no compara contra una sesión guardada: confía en los metadatos firmados. |
| `payment_checkouts.session_id`, `checkout_url` | No se guardan. Reutilizar una sesión repite la llamada con la misma clave de idempotencia y Stripe devuelve la misma respuesta. | Una reutilización hace una llamada a Stripe. El cobro pendiente se identifica por monto: si dos combinaciones de plan y modalidad cuestan lo mismo, Stripe rechazaría la clave por tener otros parámetros (503 durante 30 minutos). Los precios sembrados son todos distintos. |
| `payment_checkouts.plan_code`, `billing_period`, `provider`, `opened_at`, `completed_at` | `amount` y `created_at` en `payments`; lo demás no tiene lugar. | **Se pierde el dato histórico** en V3. Cada cobro migrado queda con el monto según el catálogo vigente. |
| `payment_webhook_events` | `processed_events` con el consumidor `StripeWebhooks`. El id `evt_...` se convierte en uuid con `UUID.nameUUIDFromBytes`; V3 aplica el mismo algoritmo. | **Se pierde `event_type`**. Un webhook reenviado después de migrar se sigue reconociendo como repetido. |

## Modo simulado

El ER no guarda la sesión, así que su identificador la lleva: `cs_sim_<clave>_<PLAN>_<MODALIDAD>`. Como el plan viene en la URL, el pago simulado comprueba que su precio sea el monto del cobro guardado. El identificador cambió de formato; la web lo trata como opaco.

## `billing_period` NOT NULL

La prueba gratuita se guarda con `MONTHLY`, y la API y los eventos la siguen mostrando con modalidad nula. Una suscripción Free se reconoce por el plan.

## `gateway_charge_id`

Guarda el id de la factura de Stripe (`in_...`), porque en modo `subscription` la sesión no trae un cobro directo. La sesión (`cs_...`) no cabe: suele pasar de 60 caracteres. Un id más largo que la columna se deja nulo. Los cobros migrados desde `payment_checkouts` quedan con este campo nulo.

## Sesiones abiertas durante la migración

Las sesiones creadas antes de V3 no traen la clave del cobro en sus metadatos. Su webhook completa el cobro pendiente más reciente del monto de ese plan y modalidad.

## Sin implementar

`payments.failure_reason` y el estado `FAILED` existen en el esquema, pero ningún flujo los escribe: el servicio no recibe `invoice.payment_failed`, igual que antes.
