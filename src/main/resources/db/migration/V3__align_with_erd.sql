-- Alinea subscription_db con el ER corregido (wiki/informe-avance2/er-corregido.md), conservando los datos.
-- Lo que el ER ya no guarda se resuelve así (detalle en DESVIACIONES.md):
--   * nombre, descripción, moneda y orden de los planes: en el código (PlanCode y Plan).
--   * precio anual: se deriva de monthly_price y annual_discount_pct.
--   * cliente, suscripción y sesión de Stripe: metadatos en Stripe (Stripe sigue guardándolos).
--   * pending_billing_period se conserva: es la única columna fuera del ER (desviación documentada).
-- Nunca se guardan datos de tarjeta.

-- 1. plans: id propio y descuento anual. El id se deriva del código para que sea el mismo en todos los ambientes.
ALTER TABLE plans
    ADD COLUMN id                  UUID,
    ADD COLUMN annual_discount_pct NUMERIC(5, 2);

UPDATE plans
SET id = md5('plan:' || code)::uuid,
    annual_discount_pct = CASE
        WHEN monthly_price = 0 THEN 0
        ELSE GREATEST(round((1 - annual_price / (monthly_price * 12)) * 100, 2), 0)
    END;

ALTER TABLE plans
    ALTER COLUMN id SET NOT NULL,
    ALTER COLUMN annual_discount_pct SET NOT NULL;

-- 2. subscriptions: plan_id, fin de la prueba y fin del periodo pagado (fecha UTC).
--    billing_period pasa a NOT NULL: la prueba gratuita queda en MONTHLY y la API la sigue mostrando sin modalidad.
--    started_at se conserva en created_at, de donde se lee desde ahora.
ALTER TABLE subscriptions
    ADD COLUMN plan_id       UUID,
    ADD COLUMN trial_ends_at TIMESTAMPTZ,
    ADD COLUMN period_end    DATE;

UPDATE subscriptions s
SET plan_id        = p.id,
    billing_period = COALESCE(s.billing_period, 'MONTHLY'),
    trial_ends_at  = CASE WHEN s.plan_code = 'FREE' THEN s.valid_until END,
    period_end     = CASE WHEN s.plan_code <> 'FREE' THEN (s.valid_until AT TIME ZONE 'UTC')::date END,
    created_at     = s.started_at
FROM plans p
WHERE p.code = s.plan_code;

-- 3. payments: reemplaza a payment_checkouts. Cada sesión pasa a ser un cobro con su monto según el catálogo vigente.
CREATE TABLE payments (
    id                UUID PRIMARY KEY,
    subscription_id   UUID           NOT NULL,
    organization_id   UUID           NOT NULL,
    idempotency_key   UUID           NOT NULL,
    gateway_charge_id VARCHAR(60),
    amount            NUMERIC(10, 2) NOT NULL,
    status            VARCHAR(15)    NOT NULL,
    failure_reason    VARCHAR(120),
    created_at        TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uk_payments_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT uk_payments_gateway_charge UNIQUE (gateway_charge_id),
    CONSTRAINT ck_payments_status CHECK (status IN ('PENDING', 'PAID', 'FAILED')),
    CONSTRAINT ck_payments_amount CHECK (amount >= 0)
);

INSERT INTO payments (id, subscription_id, organization_id, idempotency_key, amount, status, created_at)
SELECT c.id, s.id, c.organization_id, c.idempotency_key,
       CASE WHEN c.billing_period = 'ANNUAL' THEN p.annual_price ELSE p.monthly_price END,
       CASE WHEN c.status = 'COMPLETED' THEN 'PAID' ELSE 'PENDING' END,
       c.opened_at
FROM payment_checkouts c
JOIN subscriptions s ON s.organization_id = c.organization_id
JOIN plans p ON p.code = c.plan_code;

CREATE INDEX idx_payments_subscription ON payments (subscription_id);
CREATE INDEX idx_payments_pending ON payments (organization_id, created_at DESC) WHERE status = 'PENDING';

DROP TABLE payment_checkouts;

-- 4. processed_events: llave (event_id, consumer) y los webhooks de Stripe registrados aquí. event_id es uuid: el id
--    de Stripe (evt_...) se convierte con el mismo algoritmo de UUID.nameUUIDFromBytes (MD5, versión 3), para que un
--    webhook reenviado después de migrar se siga reconociendo como repetido.
ALTER TABLE processed_events ADD COLUMN consumer VARCHAR(60);
UPDATE processed_events SET consumer = 'IamEventConsumer';
ALTER TABLE processed_events ALTER COLUMN consumer SET NOT NULL;
ALTER TABLE processed_events DROP CONSTRAINT processed_events_pkey;
ALTER TABLE processed_events ADD PRIMARY KEY (event_id, consumer);

INSERT INTO processed_events (event_id, consumer, processed_at)
SELECT overlay(overlay(w.hash PLACING '3' FROM 13)
               PLACING substr('89ab', (('x' || substr(w.hash, 17, 1))::bit(4)::int & 3) + 1, 1) FROM 17)::uuid,
       'StripeWebhooks', w.received_at
FROM (SELECT md5(event_id) AS hash, received_at FROM payment_webhook_events) w;

DROP TABLE payment_webhook_events;

-- 5. outbox_events: columnas del ER. El sobre completo (con aggregateType y actorId) va en payload.
UPDATE outbox_events SET payload = jsonb_build_object(
        'eventId', id, 'eventType', event_type,
        'occurredAt', to_char(occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
        'organizationId', organization_id, 'aggregateType', aggregate_type, 'aggregateId', aggregate_id,
        'actorId', actor_id, 'version', 1, 'payload', payload);
ALTER TABLE outbox_events DROP COLUMN aggregate_type;
ALTER TABLE outbox_events DROP COLUMN actor_id;
ALTER TABLE outbox_events ALTER COLUMN aggregate_id TYPE UUID USING aggregate_id::uuid;
ALTER TABLE outbox_events ALTER COLUMN event_type TYPE VARCHAR(60);

-- 6. Quita lo que sobra y cambia la llave de plans.
ALTER TABLE subscriptions
    DROP COLUMN plan_code,
    DROP COLUMN started_at,
    DROP COLUMN valid_until,
    DROP COLUMN expired_at,
    DROP COLUMN current_period_start,
    DROP COLUMN payment_provider,
    DROP COLUMN payment_customer_id,
    DROP COLUMN payment_subscription_id;

ALTER TABLE plans DROP CONSTRAINT plans_pkey;
ALTER TABLE plans DROP CONSTRAINT ck_plans_prices;
ALTER TABLE plans
    DROP COLUMN name,
    DROP COLUMN description,
    DROP COLUMN trial_only,
    DROP COLUMN annual_price,
    DROP COLUMN currency,
    DROP COLUMN sort_order;
ALTER TABLE plans
    ADD PRIMARY KEY (id),
    ADD CONSTRAINT uk_plans_code UNIQUE (code),
    ADD CONSTRAINT ck_plans_price CHECK (monthly_price >= 0),
    ADD CONSTRAINT ck_plans_discount CHECK (annual_discount_pct BETWEEN 0 AND 100);

-- 7. Restricciones nuevas de subscriptions y payments.
ALTER TABLE subscriptions
    ALTER COLUMN plan_id SET NOT NULL,
    ALTER COLUMN billing_period SET NOT NULL,
    ALTER COLUMN status TYPE VARCHAR(15),
    ADD CONSTRAINT fk_subscriptions_plan FOREIGN KEY (plan_id) REFERENCES plans (id),
    ADD CONSTRAINT ck_subscriptions_validity CHECK (trial_ends_at IS NOT NULL OR period_end IS NOT NULL);

ALTER TABLE payments
    ADD CONSTRAINT fk_payments_subscription FOREIGN KEY (subscription_id) REFERENCES subscriptions (id);

CREATE INDEX idx_subscriptions_period_due ON subscriptions (period_end) WHERE status IN ('TRIAL', 'ACTIVE');
CREATE INDEX idx_subscriptions_trial_due ON subscriptions (trial_ends_at) WHERE status IN ('TRIAL', 'ACTIVE');
