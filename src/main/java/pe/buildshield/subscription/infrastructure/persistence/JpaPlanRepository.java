package pe.buildshield.subscription.infrastructure.persistence;

import org.springframework.stereotype.Repository;
import pe.buildshield.subscription.domain.model.Plan;
import pe.buildshield.subscription.domain.model.PlanCode;
import pe.buildshield.subscription.domain.model.PlanRepository;

import java.util.List;
import java.util.Optional;

@Repository
class JpaPlanRepository implements PlanRepository {

    private final SpringDataPlanRepository jpa;

    JpaPlanRepository(SpringDataPlanRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<Plan> findAll() {
        return jpa.findAllByOrderBySortOrder().stream().map(PlanJpaEntity::toDomain).toList();
    }

    @Override
    public Optional<Plan> findByCode(PlanCode code) {
        return jpa.findById(code.name()).map(PlanJpaEntity::toDomain);
    }
}
