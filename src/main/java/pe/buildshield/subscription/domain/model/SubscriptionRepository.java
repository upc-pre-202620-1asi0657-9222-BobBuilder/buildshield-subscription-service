package pe.buildshield.subscription.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Suscripciones, una por organización. Las lecturas se filtran por la organización del contexto. */
public interface SubscriptionRepository {

    /** La de la organización del contexto. */
    Optional<Subscription> findCurrent();

    /** Organizaciones (de todas) con la suscripción vigente y la fecha de fin ya pasada. Solo en modo sistema. */
    List<UUID> findOrganizationsDue(Instant now);

    Subscription save(Subscription subscription);
}
