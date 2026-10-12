package pe.buildshield.subscription.application;

import pe.buildshield.subscription.domain.model.CheckoutStatus;
import pe.buildshield.subscription.domain.model.PaymentCheckout;
import pe.buildshield.subscription.domain.model.PaymentCheckoutRepository;
import pe.buildshield.subscription.shared.tenant.TenantContext;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Sesiones de pago en memoria con el mismo filtro por organización que la persistencia real. */
class InMemoryCheckouts implements PaymentCheckoutRepository {

    final Map<UUID, PaymentCheckout> byId = new LinkedHashMap<>();

    @Override
    public Optional<PaymentCheckout> findBySessionId(String sessionId) {
        return mine().stream().filter(checkout -> checkout.sessionId().equals(sessionId)).findFirst();
    }

    @Override
    public List<PaymentCheckout> findOpen() {
        return mine().stream().filter(checkout -> checkout.status() == CheckoutStatus.OPEN)
                .sorted(Comparator.comparing(PaymentCheckout::openedAt).reversed()).toList();
    }

    @Override
    public PaymentCheckout save(PaymentCheckout checkout) {
        UUID id = checkout.id() == null ? UUID.randomUUID() : checkout.id();
        long version = checkout.version() == null ? 0 : checkout.version() + 1;
        PaymentCheckout saved = PaymentCheckout.restore(id, checkout.organizationId(), checkout.idempotencyKey(),
                checkout.sessionId(), checkout.plan(), checkout.billingPeriod(), checkout.provider(),
                checkout.checkoutUrl(), checkout.status(), checkout.openedAt(), checkout.completedAt(), version);
        byId.put(id, saved);
        return saved;
    }

    private List<PaymentCheckout> mine() {
        UUID organization = TenantContext.require().organizationId();
        return byId.values().stream().filter(checkout -> checkout.organizationId().equals(organization)).toList();
    }
}
