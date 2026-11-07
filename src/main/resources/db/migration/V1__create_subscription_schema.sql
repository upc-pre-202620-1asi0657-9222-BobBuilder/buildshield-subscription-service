-- Suscripción y Pagos (subscription_db). Modelo de ingresos: suscripción por número de obras activas,
-- mensual o anual (anual con descuento), mismas funciones en todos los planes. Al vencer, la cuenta queda en
-- solo lectura hasta pagar. Los pagos van por una pasarela externa: aquí nunca se guardan datos de tarjeta.

-- Catálogo de planes (global, no pertenece a una organización).
CREATE TABLE plans (
    code                 VARCHAR(10)   PRIMARY KEY,
    name                 VARCHAR(50)   NOT NULL,
    description          VARCHAR(300)  NOT NULL,
    max_active_worksites INT,
    trial_only           BOOLEAN       NOT NULL,
    monthly_price        NUMERIC(10, 2) NOT NULL,
    annual_price         NUMERIC(10, 2) NOT NULL,
    currency             VARCHAR(3)    NOT NULL,
    sort_order           INT           NOT NULL,
    CONSTRAINT ck_plans_code CHECK (code IN ('FREE', 'PLUS', 'PRO')),
    CONSTRAINT ck_plans_limit CHECK (max_active_worksites IS NULL OR max_active_worksites > 0),
    CONSTRAINT ck_plans_prices CHECK (monthly_price >= 0 AND annual_price >= 0)
);

-- Precios en soles (supuesto de diseño, ver README): el anual equivale a 10 meses (dos meses de descuento).
-- max_active_worksites nulo = obras ilimitadas.
INSERT INTO plans (code, name, description, max_active_worksites, trial_only, monthly_price, annual_price, currency,
                   sort_order)
VALUES ('FREE', 'Free', 'Prueba gratuita con todas las funciones', NULL, TRUE, 0.00, 0.00, 'PEN', 1),
       ('PLUS', 'Plus', 'Hasta 5 obras activas, todas las funciones', 5, FALSE, 299.00, 2990.00, 'PEN', 2),
       ('PRO', 'Pro', 'Obras ilimitadas, todas las funciones', NULL, FALSE, 699.00, 6990.00, 'PEN', 3);

-- Una suscripción vigente por organización (la organización es el tenant).
CREATE TABLE subscriptions (
    id              UUID PRIMARY KEY,
    organization_id UUID        NOT NULL,
    plan_code       VARCHAR(10) NOT NULL REFERENCES plans (code),
    billing_period  VARCHAR(10),
    status          VARCHAR(10) NOT NULL,
    started_at      TIMESTAMPTZ NOT NULL,
    valid_until     TIMESTAMPTZ NOT NULL,
    expired_at      TIMESTAMPTZ,
    version         BIGINT      NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    created_by      UUID        NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    updated_by      UUID        NOT NULL,
    CONSTRAINT uk_subscriptions_organization UNIQUE (organization_id),
    CONSTRAINT ck_subscriptions_status CHECK (status IN ('TRIAL', 'ACTIVE', 'EXPIRED')),
    CONSTRAINT ck_subscriptions_period CHECK (billing_period IS NULL OR billing_period IN ('MONTHLY', 'ANNUAL')),
    CONSTRAINT ck_subscriptions_dates CHECK (valid_until >= started_at),
    CONSTRAINT ck_subscriptions_expired CHECK (status <> 'EXPIRED' OR expired_at IS NOT NULL)
);

CREATE INDEX idx_subscriptions_due ON subscriptions (valid_until) WHERE status IN ('TRIAL', 'ACTIVE');

-- Transactional Outbox (kernel compartido): eventos escritos en la misma transacción que el cambio de
-- negocio; OutboxPublisher los envía a Kafka y marca published_at.
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY,
    event_type      VARCHAR(100) NOT NULL,
    aggregate_type  VARCHAR(50)  NOT NULL,
    aggregate_id    VARCHAR(100) NOT NULL,
    organization_id UUID         NOT NULL,
    actor_id        UUID,
    correlation_id  VARCHAR(64),
    payload         JSONB        NOT NULL,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    published_at    TIMESTAMPTZ
);

CREATE INDEX idx_outbox_events_pending ON outbox_events (occurred_at, id) WHERE published_at IS NULL;

-- Consumidor idempotente (kernel compartido): un evento se procesa una sola vez.
CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);
