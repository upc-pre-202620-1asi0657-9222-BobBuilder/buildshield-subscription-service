-- Sprint 4 (US07, US09, US11 y US12): pago real del plan con la pasarela externa (Stripe Checkout), webhook firmado
-- y cambio de modalidad programado. Nunca se guardan datos de tarjeta: solo los identificadores de la pasarela.

-- Datos del cobro de la suscripción pagada. current_period_start: inicio del periodo actual (prueba o último cobro).
-- pending_billing_period: modalidad que rige desde el siguiente periodo de cobro (valid_until).
ALTER TABLE subscriptions
    ADD COLUMN current_period_start    TIMESTAMPTZ,
    ADD COLUMN pending_billing_period  VARCHAR(10),
    ADD COLUMN payment_provider        VARCHAR(10),
    ADD COLUMN payment_customer_id     VARCHAR(100),
    ADD COLUMN payment_subscription_id VARCHAR(100);

UPDATE subscriptions SET current_period_start = started_at WHERE current_period_start IS NULL;

ALTER TABLE subscriptions
    ALTER COLUMN current_period_start SET NOT NULL,
    ADD CONSTRAINT ck_subscriptions_pending_period
        CHECK (pending_billing_period IS NULL OR pending_billing_period IN ('MONTHLY', 'ANNUAL')),
    ADD CONSTRAINT ck_subscriptions_provider
        CHECK (payment_provider IS NULL OR payment_provider IN ('STRIPE', 'SIMULATED'));

-- La renovación (invoice.paid) llega con la suscripción de la pasarela: se busca la organización por ese id.
CREATE UNIQUE INDEX uk_subscriptions_payment_subscription
    ON subscriptions (payment_subscription_id) WHERE payment_subscription_id IS NOT NULL;

-- Sesiones de pago (Stripe Checkout o simuladas). idempotency_key: clave enviada a la pasarela al crear la sesión.
CREATE TABLE payment_checkouts (
    id              UUID PRIMARY KEY,
    organization_id UUID          NOT NULL,
    idempotency_key UUID          NOT NULL,
    session_id      VARCHAR(255)  NOT NULL,
    plan_code       VARCHAR(10)   NOT NULL REFERENCES plans (code),
    billing_period  VARCHAR(10)   NOT NULL,
    provider        VARCHAR(10)   NOT NULL,
    checkout_url    VARCHAR(2000) NOT NULL,
    status          VARCHAR(10)   NOT NULL,
    opened_at       TIMESTAMPTZ   NOT NULL,
    completed_at    TIMESTAMPTZ,
    version         BIGINT        NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    created_by      UUID          NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    updated_by      UUID          NOT NULL,
    CONSTRAINT uk_payment_checkouts_session UNIQUE (session_id),
    CONSTRAINT uk_payment_checkouts_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_payment_checkouts_plan CHECK (plan_code IN ('PLUS', 'PRO')),
    CONSTRAINT ck_payment_checkouts_period CHECK (billing_period IN ('MONTHLY', 'ANNUAL')),
    CONSTRAINT ck_payment_checkouts_provider CHECK (provider IN ('STRIPE', 'SIMULATED')),
    CONSTRAINT ck_payment_checkouts_status CHECK (status IN ('OPEN', 'COMPLETED')),
    CONSTRAINT ck_payment_checkouts_completed CHECK (status <> 'COMPLETED' OR completed_at IS NOT NULL)
);

CREATE INDEX idx_payment_checkouts_open ON payment_checkouts (organization_id, opened_at DESC) WHERE status = 'OPEN';

-- Webhooks de la pasarela ya procesados (deduplicación por id de evento, evt_...). Stripe entrega al menos una vez.
CREATE TABLE payment_webhook_events (
    event_id    VARCHAR(255) PRIMARY KEY,
    event_type  VARCHAR(100) NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL
);
