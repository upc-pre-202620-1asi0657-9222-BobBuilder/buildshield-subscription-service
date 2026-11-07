package pe.buildshield.subscription.application;

import pe.buildshield.subscription.domain.model.Payment;
import pe.buildshield.subscription.domain.model.PaymentRepository;
import pe.buildshield.subscription.domain.model.PaymentStatus;
import pe.buildshield.subscription.shared.tenant.TenantContext;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Cobros en memoria con el mismo filtro por organización que la persistencia real. */
class InMemoryPayments implements PaymentRepository {

    final Map<UUID, Payment> byId = new LinkedHashMap<>();

    @Override
    public Optional<Payment> findByIdempotencyKey(UUID idempotencyKey) {
        return mine().stream().filter(payment -> payment.idempotencyKey().equals(idempotencyKey)).findFirst();
    }

    @Override
    public List<Payment> findPending() {
        return mine().stream().filter(payment -> payment.status() == PaymentStatus.PENDING)
                .sorted(Comparator.comparing(Payment::createdAt).reversed()).toList();
    }

    @Override
    public Payment save(Payment payment) {
        UUID id = payment.id() == null ? UUID.randomUUID() : payment.id();
        Payment saved = Payment.restore(id, payment.subscriptionId(), payment.organizationId(),
                payment.idempotencyKey(), payment.gatewayChargeId(), payment.amount(), payment.status(),
                payment.failureReason(), payment.createdAt());
        byId.put(id, saved);
        return saved;
    }

    private List<Payment> mine() {
        UUID organization = TenantContext.require().organizationId();
        return byId.values().stream().filter(payment -> payment.organizationId().equals(organization)).toList();
    }
}
