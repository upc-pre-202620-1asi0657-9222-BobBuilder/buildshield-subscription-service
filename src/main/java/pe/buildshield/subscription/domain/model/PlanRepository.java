package pe.buildshield.subscription.domain.model;

import java.util.List;
import java.util.Optional;

/** Catálogo de planes, ordenado como se muestra al cliente. */
public interface PlanRepository {

    List<Plan> findAll();

    Optional<Plan> findByCode(PlanCode code);
}
