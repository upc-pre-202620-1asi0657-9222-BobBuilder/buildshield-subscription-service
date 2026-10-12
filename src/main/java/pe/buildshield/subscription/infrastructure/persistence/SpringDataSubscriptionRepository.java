package pe.buildshield.subscription.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataSubscriptionRepository extends JpaRepository<SubscriptionJpaEntity, UUID> {

    /** El filtro multiempresa deja solo la de la organización del contexto. */
    Optional<SubscriptionJpaEntity> findFirstByOrderByCreatedAtDesc();

    @Query(value = """
            SELECT organization_id FROM subscriptions
            WHERE status IN ('TRIAL', 'ACTIVE') AND valid_until <= :now
            ORDER BY valid_until
            """, nativeQuery = true)
    List<UUID> findOrganizationsDue(@Param("now") Instant now);

    @Query(value = "SELECT organization_id FROM subscriptions WHERE payment_subscription_id = :paymentSubscriptionId",
            nativeQuery = true)
    Optional<UUID> findOrganizationByPaymentSubscriptionId(
            @Param("paymentSubscriptionId") String paymentSubscriptionId);
}
