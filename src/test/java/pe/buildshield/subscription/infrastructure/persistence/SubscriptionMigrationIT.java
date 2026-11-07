package pe.buildshield.subscription.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import pe.buildshield.subscription.support.SubscriptionIntegrationTest;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V3 con datos: se migra hasta V2 en un esquema aparte, se cargan filas del modelo anterior y se comprueba que pasan al
 * modelo del ER sin perderse.
 */
@SubscriptionIntegrationTest
class SubscriptionMigrationIT {

    private static final String SCHEMA = "migration_it";

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    private final UUID system = UUID.randomUUID();
    private final UUID trialOrganization = UUID.randomUUID();
    private final UUID paidOrganization = UUID.randomUUID();
    private final UUID trialSubscription = UUID.randomUUID();
    private final UUID paidSubscription = UUID.randomUUID();
    private final Timestamp start = Timestamp.from(Instant.parse("2026-10-01T10:00:00Z"));
    private final Timestamp later = Timestamp.from(Instant.parse("2026-10-01T10:00:05Z"));

    @BeforeEach
    void migrateToV2() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        flyway("2").migrate();
    }

    @AfterEach
    void dropSchema() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @Test
    void the_old_rows_move_to_the_erd_model() {
        insertOldRows();

        flyway("latest").migrate();

        assertThat(jdbc.queryForObject("SELECT annual_discount_pct FROM " + SCHEMA + ".plans WHERE code = 'PLUS'",
                java.math.BigDecimal.class)).isEqualByComparingTo("16.67");
        assertThat(jdbc.queryForObject("SELECT annual_discount_pct FROM " + SCHEMA + ".plans WHERE code = 'FREE'",
                java.math.BigDecimal.class)).isEqualByComparingTo("0");

        var trial = jdbc.queryForMap("""
                SELECT p.code, s.billing_period, s.status, s.trial_ends_at, s.period_end, s.created_at
                FROM %1$s.subscriptions s JOIN %1$s.plans p ON p.id = s.plan_id WHERE s.id = ?
                """.formatted(SCHEMA), trialSubscription);
        assertThat(trial).containsEntry("code", "FREE").containsEntry("billing_period", "MONTHLY")
                .containsEntry("status", "TRIAL").containsEntry("period_end", null);
        assertThat(((Timestamp) trial.get("trial_ends_at")).toInstant()).isEqualTo(Instant.parse("2026-10-15T10:00:00Z"));
        assertThat(((Timestamp) trial.get("created_at")).toInstant()).isEqualTo(start.toInstant());

        var paid = jdbc.queryForMap("""
                SELECT p.code, s.billing_period, s.status, s.trial_ends_at, s.period_end, s.pending_billing_period,
                       s.gateway_subscription_id
                FROM %1$s.subscriptions s JOIN %1$s.plans p ON p.id = s.plan_id WHERE s.id = ?
                """.formatted(SCHEMA), paidSubscription);
        assertThat(paid).containsEntry("code", "PLUS").containsEntry("billing_period", "ANNUAL")
                .containsEntry("status", "ACTIVE").containsEntry("trial_ends_at", null)
                .containsEntry("pending_billing_period", "MONTHLY")
                .containsEntry("gateway_subscription_id", null)
                .containsEntry("period_end", Date.valueOf("2027-10-01"));

        var payments = jdbc.queryForList("""
                SELECT subscription_id, amount, status, gateway_charge_id FROM %s.payments ORDER BY status
                """.formatted(SCHEMA));
        assertThat(payments).hasSize(2);
        assertThat(payments.get(0)).containsEntry("subscription_id", paidSubscription).containsEntry("status", "PAID")
                .containsEntry("gateway_charge_id", null);
        assertThat((java.math.BigDecimal) payments.get(0).get("amount")).isEqualByComparingTo("2990.00");
        assertThat(payments.get(1)).containsEntry("subscription_id", trialSubscription)
                .containsEntry("status", "PENDING");
        assertThat((java.math.BigDecimal) payments.get(1).get("amount")).isEqualByComparingTo("699.00");

        UUID stripeEvent = UUID.nameUUIDFromBytes("evt_viejo_1".getBytes(StandardCharsets.UTF_8));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM %s.processed_events WHERE event_id = ? AND consumer = 'StripeWebhooks'"
                .formatted(SCHEMA), Long.class, stripeEvent)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT consumer FROM %s.processed_events WHERE event_id <> ?".formatted(SCHEMA),
                String.class, stripeEvent)).isEqualTo("IamEventConsumer");

        var outbox = jdbc.queryForMap("""
                SELECT aggregate_id, payload ->> 'aggregateType' AS type, payload ->> 'actorId' AS actor,
                       payload -> 'payload' ->> 'status' AS status, payload ->> 'eventType' AS event
                FROM %s.outbox_events
                """.formatted(SCHEMA));
        assertThat(outbox).containsEntry("aggregate_id", paidSubscription).containsEntry("type", "SUBSCRIPTION")
                .containsEntry("status", "ACTIVE").containsEntry("event", "SubscriptionActivated")
                .containsEntry("actor", system.toString());
    }

    @Test
    void the_schema_is_the_one_of_the_erd() {
        flyway("latest").migrate();

        assertThat(columns("plans")).containsExactlyInAnyOrder("id", "code", "max_active_worksites", "monthly_price",
                "annual_discount_pct");
        assertThat(columns("subscriptions")).containsExactlyInAnyOrder("id", "organization_id", "plan_id",
                "billing_period", "status", "trial_ends_at", "period_end", "version", "created_at", "created_by",
                "updated_at", "updated_by", "pending_billing_period", "gateway_subscription_id");
        var gatewayId = jdbc.queryForMap("""
                SELECT data_type, character_maximum_length, is_nullable FROM information_schema.columns
                WHERE table_schema = ? AND table_name = 'subscriptions' AND column_name = 'gateway_subscription_id'
                """, SCHEMA);
        assertThat(gatewayId).containsEntry("data_type", "character varying")
                .containsEntry("character_maximum_length", 60).containsEntry("is_nullable", "YES");
        assertThat(columns("payments")).containsExactlyInAnyOrder("id", "subscription_id", "organization_id",
                "idempotency_key", "gateway_charge_id", "amount", "status", "failure_reason", "created_at");
        assertThat(columns("outbox_events")).containsExactlyInAnyOrder("id", "organization_id", "aggregate_id",
                "event_type", "correlation_id", "payload", "occurred_at", "published_at");
        assertThat(columns("processed_events")).containsExactlyInAnyOrder("event_id", "consumer", "processed_at");
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = ? AND table_name LIKE 'payment%'
                """, String.class, SCHEMA)).containsExactly("payments");
    }

    private void insertOldRows() {
        String subscriptions = SCHEMA + ".subscriptions";
        jdbc.update("""
                INSERT INTO %s (id, organization_id, plan_code, billing_period, status, started_at, valid_until, version,
                    created_at, created_by, updated_at, updated_by, current_period_start)
                VALUES (?, ?, 'FREE', NULL, 'TRIAL', ?, ?, 0, ?, ?, ?, ?, ?)
                """.formatted(subscriptions), trialSubscription, trialOrganization, start,
                Timestamp.from(Instant.parse("2026-10-15T10:00:00Z")), later, system, later, system, start);
        jdbc.update("""
                INSERT INTO %s (id, organization_id, plan_code, billing_period, status, started_at, valid_until, version,
                    created_at, created_by, updated_at, updated_by, current_period_start, pending_billing_period,
                    payment_provider, payment_customer_id, payment_subscription_id)
                VALUES (?, ?, 'PLUS', 'ANNUAL', 'ACTIVE', ?, ?, 2, ?, ?, ?, ?, ?, 'MONTHLY', 'STRIPE', 'cus_1', 'sub_1')
                """.formatted(subscriptions), paidSubscription, paidOrganization, start,
                Timestamp.from(Instant.parse("2027-10-01T15:30:00Z")), later, system, later, system, start);
        insertCheckout(paidOrganization, "cs_pagada", "PLUS", "ANNUAL", "COMPLETED", start);
        insertCheckout(trialOrganization, "cs_abierta", "PRO", "MONTHLY", "OPEN", null);
        jdbc.update("INSERT INTO %s.payment_webhook_events (event_id, event_type, received_at) VALUES (?, ?, ?)"
                .formatted(SCHEMA), "evt_viejo_1", "invoice.paid", later);
        jdbc.update("INSERT INTO %s.processed_events (event_id, processed_at) VALUES (?, ?)".formatted(SCHEMA),
                UUID.randomUUID(), later);
        jdbc.update("""
                INSERT INTO %s.outbox_events (id, event_type, aggregate_type, aggregate_id, organization_id, actor_id,
                    payload, occurred_at)
                VALUES (?, 'SubscriptionActivated', 'SUBSCRIPTION', ?, ?, ?, CAST(? AS jsonb), ?)
                """.formatted(SCHEMA), UUID.randomUUID(), paidSubscription.toString(), paidOrganization, system,
                "{\"status\":\"ACTIVE\"}", later);
    }

    private void insertCheckout(UUID organization, String session, String plan, String period, String status,
            Timestamp completedAt) {
        jdbc.update("""
                INSERT INTO %s.payment_checkouts (id, organization_id, idempotency_key, session_id, plan_code,
                    billing_period, provider, checkout_url, status, opened_at, completed_at, version, created_at,
                    created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, 'STRIPE', 'https://s', ?, ?, ?, 0, ?, ?, ?, ?)
                """.formatted(SCHEMA), UUID.randomUUID(), organization, UUID.randomUUID(), session, plan, period, status,
                start, completedAt, start, system, start, system);
    }

    private java.util.List<String> columns(String table) {
        return jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ?
                """, String.class, SCHEMA, table);
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).schemas(SCHEMA).locations("classpath:db/migration")
                .target(target).load();
    }
}
