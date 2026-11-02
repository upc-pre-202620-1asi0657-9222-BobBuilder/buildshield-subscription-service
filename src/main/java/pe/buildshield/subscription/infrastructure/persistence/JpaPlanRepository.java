package pe.buildshield.subscription.infrastructure.persistence;

import org.springframework.stereotype.Repository;
import pe.buildshield.subscription.domain.model.Plan;
import pe.buildshield.subscription.domain.model.PlanCode;
import pe.buildshield.subscription.domain.model.PlanRepository;

import java.util.Comparator;
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
        return jpa.findAll().stream().map(PlanJpaEntity::toDomain).sorted(Comparator.comparing(Plan::code)).toList();
    }

    @Override
    public Optional<Plan> findByCode(PlanCode code) {
        return jpa.findByCode(code.name()).map(PlanJpaEntity::toDomain);
    }
}
