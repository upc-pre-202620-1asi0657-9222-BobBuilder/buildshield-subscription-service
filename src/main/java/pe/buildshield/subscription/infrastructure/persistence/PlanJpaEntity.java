package pe.buildshield.subscription.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import pe.buildshield.subscription.domain.model.Plan;
import pe.buildshield.subscription.domain.model.PlanCode;

import java.math.BigDecimal;

/** Plan del catálogo global (no pertenece a una organización): solo lectura, se carga con Flyway. */
@Entity
@Table(name = "plans")
public class PlanJpaEntity {

    @Id
    @Column(name = "code", length = 10)
    private String code;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "description", nullable = false, length = 300)
    private String description;

    @Column(name = "max_active_worksites")
    private Integer maxActiveWorksites;

    @Column(name = "trial_only", nullable = false)
    private boolean trialOnly;

    @Column(name = "monthly_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal monthlyPrice;

    @Column(name = "annual_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal annualPrice;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected PlanJpaEntity() {
    }

    Plan toDomain() {
        return new Plan(PlanCode.valueOf(code), name, description, maxActiveWorksites, trialOnly, monthlyPrice,
                annualPrice, currency);
    }
}
