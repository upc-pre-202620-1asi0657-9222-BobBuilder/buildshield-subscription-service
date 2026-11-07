package pe.buildshield.subscription.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataSubscriptionRepository extends JpaRepository<SubscriptionJpaEntity, UUID> {

    /** El filtro multiempresa deja solo la de la organización del contexto. */
    Optional<SubscriptionJpaEntity> findFirstByOrderByCreatedAtDesc();

    /** {@code today} es la fecha UTC de {@code now}: un periodo pagado termina a las 00:00 UTC de {@code period_end}. */
    @Query(value = """
            SELECT organization_id FROM subscriptions
            WHERE status IN ('TRIAL', 'ACTIVE')
              AND (period_end <= :today OR (period_end IS NULL AND trial_ends_at <= :now))
            ORDER BY COALESCE(period_end, CAST(trial_ends_at AS date))
            """, nativeQuery = true)
    List<UUID> findOrganizationsDue(@Param("now") Instant now, @Param("today") LocalDate today);
}
