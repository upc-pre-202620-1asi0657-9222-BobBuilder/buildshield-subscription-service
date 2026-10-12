package pe.buildshield.subscription.application;

import pe.buildshield.subscription.domain.model.Subscription;
import pe.buildshield.subscription.domain.model.SubscriptionRepository;
import pe.buildshield.subscription.shared.tenant.TenantContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Repositorio en memoria con el mismo filtro por organización que la persistencia real. */
class InMemorySubscriptions implements SubscriptionRepository {

    final Map<UUID, Subscription> byOrganization = new LinkedHashMap<>();
    final List<Boolean> systemModeQueries = new ArrayList<>();

    @Override
    public Optional<Subscription> findCurrent() {
        // Copia, como la persistencia real: un cambio no guardado no altera lo almacenado.
        return Optional.ofNullable(byOrganization.get(TenantContext.require().organizationId())).map(stored ->
                Subscription.restore(stored.id(), stored.organizationId(), stored.plan(), stored.billingPeriod(),
                        stored.status(), stored.startedAt(), stored.validUntil(), stored.expiredAt(), stored.payment(),
                        stored.version()));
    }

    @Override
    public List<UUID> findOrganizationsDue(Instant now) {
        systemModeQueries.add(TenantContext.isSystem());
        return byOrganization.values().stream().filter(subscription -> subscription.isDue(now))
                .map(Subscription::organizationId).toList();
    }

    @Override
    public Optional<UUID> findOrganizationByPaymentSubscriptionId(String paymentSubscriptionId) {
        systemModeQueries.add(TenantContext.isSystem());
        return byOrganization.values().stream()
                .filter(subscription -> paymentSubscriptionId.equals(subscription.paymentSubscriptionId()))
                .map(Subscription::organizationId).findFirst();
    }

    @Override
    public Subscription save(Subscription subscription) {
        long version = subscription.version() == null ? 0 : subscription.version() + 1;
        Subscription saved = Subscription.restore(subscription.id() == null ? UUID.randomUUID() : subscription.id(),
                subscription.organizationId(), subscription.plan(), subscription.billingPeriod(),
                subscription.status(), subscription.startedAt(), subscription.validUntil(), subscription.expiredAt(),
                subscription.payment(), version);
        byOrganization.put(saved.organizationId(), saved);
        return saved;
    }
}
