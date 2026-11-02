package pe.buildshield.subscription.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import pe.buildshield.subscription.domain.model.Plan;
import pe.buildshield.subscription.domain.model.PlanCode;

import java.math.BigDecimal;
import java.util.UUID;

/** Plan del catálogo global (no pertenece a una organización): solo lectura, se carga con Flyway. */
@Entity
@Table(name = "plans")
public class PlanJpaEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "code", nullable = false, unique = true, length = 10)
    private String code;

    @Column(name = "max_active_worksites")
    private Integer maxActiveWorksites;

    @Column(name = "monthly_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal monthlyPrice;

    @Column(name = "annual_discount_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal annualDiscountPct;

    protected PlanJpaEntity() {
    }

    Plan toDomain() {
        return new Plan(PlanCode.valueOf(code), maxActiveWorksites, monthlyPrice, annualDiscountPct);
    }
}
