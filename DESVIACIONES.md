# Desviaciones frente al ER

Fuente: sección `subscription_db` de `wiki/informe-avance2/er-corregido.md`. Este archivo lista lo que el servicio tiene y el ER no dibuja, y cómo se resolvió lo que el ER quitó.

## Columnas que el equipo agregó al ER

El equipo decidió ampliar el ER con estas dos columnas de `subscriptions`. Ya no son desviaciones, y ninguna columna del servicio queda fuera del ER. `wiki/informe-avance2/er-corregido.md` vive fuera de este repositorio y al escribir esto todavía no las dibuja.

| Columna | Tipo | Motivo |
|---|---|---|
| `pending_billing_period` | `varchar(10) null` (desde V2) | US12 pide mostrar el cambio de modalidad programado y aplicarlo al renovar. `GET /subscriptions/current` y los eventos lo exponen en cada lectura. En Stripe vive en el subscription schedule, pero leerlo ahí haría depender cada consulta de Stripe, y el modo simulado no tiene dónde guardarlo. |
| `gateway_subscription_id` | `varchar(60) null` (V4) | Id de la suscripción de Stripe (`sub_...`). US12 lo usa para leerla directo con `GET /v1/subscriptions/{id}`. Lo escribe el webhook: `subscription` de `checkout.session.completed` (o `checkout.session.async_payment_succeeded`) al activar, y `subscription` o `parent.subscription_details.subscription` de `invoice.paid` al renovar. |

`outbox_events.correlation_id` y la auditoría de `subscriptions` sí están en el ER.

## Cómo US12 ubica la suscripción de Stripe

1. **Con `gateway_subscription_id`:** `GET /v1/subscriptions/{id}`. Si Stripe responde 404 o la suscripción está cancelada, se responde 503 y no se busca por metadatos: solo la falta del id activa el respaldo.
2. **Con la columna en NULL:** `GET /v1/subscriptions/search` por `metadata['organizationId']`, solo como respaldo. Esa búsqueda puede demorar hasta un minuto y no está disponible en todas las regiones de Stripe. Si la región no la tiene, el cambio de modalidad de esas filas responde 503 hasta que un webhook de renovación guarde el id.

La columna queda en NULL en estos casos:

- Las filas anteriores a V4: V3 ya borró `payment_subscription_id` y no hay de dónde copiar el id. Se llena con la siguiente renovación.
- Un webhook de activación sin `subscription`. La activación reemplaza el id guardado aunque venga vacío, porque cada pago de Checkout crea otra suscripción en Stripe y el id anterior ya no sirve. Una renovación sin id conserva el guardado.
- Un id de más de 60 caracteres, que no se guarda (igual que `gateway_charge_id`).

**Sin credenciales, todo esto se probó solo contra un Stripe simulado (servidor HTTP local con eventos escritos a mano). Nunca se usó el Stripe real.** Falta confirmar con una cuenta de prueba los campos de los webhooks y si la región de la cuenta tiene la búsqueda.

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
| `subscriptions.payment_subscription_id` | Reemplazada por `gateway_subscription_id` (V4), con 60 caracteres en vez de 100. La organización sigue en los metadatos: `invoice.paid` trae `organizationId` en `subscription_details.metadata` (o `parent.subscription_details.metadata`). | V3 borró los valores antiguos y V4 no puede recuperarlos: esas filas usan el respaldo descrito arriba. |
| `payment_checkouts` | `payments`. El plan, la modalidad y la clave del cobro viajan en los metadatos de la sesión, protegidos por la firma del webhook. | La activación ya no compara contra una sesión guardada: confía en los metadatos firmados. |
| `payment_checkouts.session_id`, `checkout_url` | No se guardan. Reutilizar una sesión repite la llamada con la misma clave de idempotencia y Stripe devuelve la misma respuesta. | Una reutilización hace una llamada a Stripe. El cobro pendiente se identifica por monto: si dos combinaciones de plan y modalidad cuestan lo mismo, Stripe rechazaría la clave por tener otros parámetros (503 durante 30 minutos). Los precios sembrados son todos distintos. |
| `payment_checkouts.plan_code`, `billing_period`, `provider`, `opened_at`, `completed_at` | `amount` y `created_at` en `payments`; lo demás no tiene lugar. | **Se pierde el dato histórico** en V3. Cada cobro migrado queda con el monto según el catálogo vigente. |
| `payment_webhook_events` | `processed_events` con el consumidor `StripeWebhooks`. El id `evt_...` se convierte en uuid con `UUID.nameUUIDFromBytes`; V3 aplica el mismo algoritmo. | **Se pierde `event_type`**. Un webhook reenviado después de migrar se sigue reconociendo como repetido. |

## Modo simulado

El ER no guarda la sesión, así que su identificador la lleva: `cs_sim_<clave>_<PLAN>_<MODALIDAD>`. Como el plan viene en la URL, el pago simulado comprueba que su precio sea el monto del cobro guardado. El identificador cambió de formato; la web lo trata como opaco.

El evento simulado trae `sub_sim_<clave>` como suscripción y se guarda en `gateway_subscription_id`, igual que el real, pero el modo simulado no llama a ninguna pasarela. Una base local con esos ids que luego arranca con clave de Stripe recibe 404 de Stripe en US12, y por lo tanto 503, hasta que llegue un pago real.

## `billing_period` NOT NULL

La prueba gratuita se guarda con `MONTHLY`, y la API y los eventos la siguen mostrando con modalidad nula. Una suscripción Free se reconoce por el plan.

## `gateway_charge_id`

Guarda el id de la factura de Stripe (`in_...`), porque en modo `subscription` la sesión no trae un cobro directo. La sesión (`cs_...`) no cabe: suele pasar de 60 caracteres. Un id más largo que la columna se deja nulo. Los cobros migrados desde `payment_checkouts` quedan con este campo nulo.

## Sesiones abiertas durante la migración

Las sesiones creadas antes de V3 no traen la clave del cobro en sus metadatos. Su webhook completa el cobro pendiente más reciente del monto de ese plan y modalidad.

## Sin implementar

`payments.failure_reason` y el estado `FAILED` existen en el esquema, pero ningún flujo los escribe: el servicio no recibe `invoice.payment_failed`, igual que antes.
