package pe.buildshield.subscription.architecture.fixtures.gamma.interfaces;

import pe.buildshield.subscription.architecture.fixtures.alpha.infrastructure.AlphaRepository;

/** Clase de ejemplo: un controlador que accede a un repositorio (violación A.11). */
public class GammaController {

    private final AlphaRepository repository = new AlphaRepository();

    public String endpoint() {
        return repository.find();
    }
}
