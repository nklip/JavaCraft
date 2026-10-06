package dev.nklip.javacraft.shardshop.idgen;

/** The caller must abort new-ID work; future HTTP providers map this failure to HTTP 503. */
public final class IdGenerationUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    IdGenerationUnavailableException(RuntimeException cause) {
        super("ID_GENERATION_UNAVAILABLE", cause);
    }
}
