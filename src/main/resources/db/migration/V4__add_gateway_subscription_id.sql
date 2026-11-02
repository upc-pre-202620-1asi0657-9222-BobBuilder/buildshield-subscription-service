-- El equipo amplió el ER: subscriptions guarda el id de la suscripción de Stripe (sub_...) y acepta
-- pending_billing_period, que ya existe desde V2. Reemplaza lo dicho en los comentarios de V3 sobre ambas.
-- Las filas existentes quedan en NULL hasta el próximo webhook: V3 ya borró payment_subscription_id, no hay de dónde
-- copiarlo. Con NULL, el cambio de modalidad (US12) ubica la suscripción con la búsqueda por metadatos.
ALTER TABLE subscriptions ADD COLUMN gateway_subscription_id VARCHAR(60) NULL;
