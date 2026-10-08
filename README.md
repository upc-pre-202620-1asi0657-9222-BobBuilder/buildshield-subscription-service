# buildshield-subscription-service

Suscripción y Pagos de BuildShield (BobBuilder): catálogo de planes, prueba gratuita al registrar la organización y
vencimiento de la suscripción (cuenta en solo lectura). Contrato de la arquitectura:
`wiki/informe-tf/plan-microservicios.md` del proyecto.

| Dato | Valor |
|---|---|
| Puerto | 8086 |
| Base | `subscription_db` (PostgreSQL 16+) |
| Paquete | `pe.buildshield.subscription` (kernel compartido copiado de iam en `pe.buildshield.subscription.shared`) |
| Publica | `buildshield.subscription`: `SubscriptionActivated` (al iniciar la prueba) y `SubscriptionExpired` (al vencer). Payload: `organizationId`, `subscriptionId`, `plan` (FREE, PLUS, PRO), `status` (TRIAL, ACTIVE, EXPIRED), `validUntil`, `billingPeriod`, `startedAt` |
| Consume | `buildshield.iam`: `OrganizationRegistered` (inicia la prueba gratuita de 14 días, una sola vez por organización) |

## Modelo de ingresos

- **Free**: solo prueba gratuita, con todas las funciones, por `SUBSCRIPTION_TRIAL_DAYS` días (14 por defecto).
- **Plus**: hasta 5 obras activas. **Pro**: obras ilimitadas. Mismas funciones en todos los planes.
- Mensual o anual; el anual equivale a 10 meses (16,67 % de descuento). Precios sembrados por Flyway en soles
  (Plus S/ 299 al mes o S/ 2 990 al año, Pro S/ 699 o S/ 6 990): son supuestos de diseño, se cambian en `plans`.
- Al vencer, la suscripción pasa a `EXPIRED` y se publica `SubscriptionExpired`; iam deja la cuenta en solo lectura.
  El vencimiento lo revisa un proceso periódico (`SUBSCRIPTION_EXPIRY_CHECK_INTERVAL`, un minuto por defecto).
- Pagos: `PaymentGateway` es el puerto de la pasarela externa y `StripeAdapter` su adaptador, todavía sin
  integración real (Sprint 4, US10 y US11). El servicio nunca recibe ni guarda datos de tarjeta.

## Endpoints

- `GET /api/v1/plans`: planes con límite de obras, precio mensual y anual y descuento anual (cualquier rol).
- `GET /api/v1/subscriptions/current`: plan, estado, fecha de fin, días restantes y `readOnly` de la organización del
  token (cualquier rol); 404 si la organización todavía no tiene suscripción.
- OpenAPI en `/api/v1/api-docs` y `/swagger-ui.html` (perfiles local y test).

## Variables de entorno

`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS` (por defecto `localhost:9092`),
`BUILDSHIELD_JWT_PUBLIC_KEY`, `SUBSCRIPTION_TRIAL_DAYS` y `SUBSCRIPTION_EXPIRY_CHECK_INTERVAL`. Perfiles `local`,
`test` y `prod`.

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
gratuita) y US09 (estado de la suscripción y planes). JaCoCo exige 80 % de líneas en `domain` y `application`.
