package pe.buildshield.subscription.infrastructure.security;

import org.springframework.stereotype.Component;
import pe.buildshield.subscription.shared.security.RevokedTokenStore;

/**
 * Revocación de tokens para el filtro JWT del kernel. La lista de tokens cerrados vive en iam y este servicio no
 * guarda copia de usuarios: se confía en la firma y en la expiración de 15 minutos del token.
 */
@Component
class NoRevocationTokenStore implements RevokedTokenStore {

    @Override
    public boolean isRevoked(String tokenId) {
        return false;
    }
}
