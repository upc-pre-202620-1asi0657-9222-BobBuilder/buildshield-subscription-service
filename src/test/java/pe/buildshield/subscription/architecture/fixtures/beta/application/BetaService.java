package pe.buildshield.subscription.architecture.fixtures.beta.application;

import pe.buildshield.subscription.architecture.fixtures.alpha.infrastructure.AlphaRepository;

/** Clase de ejemplo: un caso de uso que usa un adaptador de infraestructura (violación de capas). */
public class BetaService {

    private final AlphaRepository repository = new AlphaRepository();

    public String run() {
        return repository.find();
    }
}
