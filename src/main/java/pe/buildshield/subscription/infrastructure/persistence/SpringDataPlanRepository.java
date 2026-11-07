package pe.buildshield.subscription.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface SpringDataPlanRepository extends JpaRepository<PlanJpaEntity, UUID> {

    Optional<PlanJpaEntity> findByCode(String code);
}
