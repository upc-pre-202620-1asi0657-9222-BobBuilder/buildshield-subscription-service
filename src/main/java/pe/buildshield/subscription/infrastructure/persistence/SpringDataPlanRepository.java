package pe.buildshield.subscription.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface SpringDataPlanRepository extends JpaRepository<PlanJpaEntity, String> {

    List<PlanJpaEntity> findAllByOrderBySortOrder();
}
