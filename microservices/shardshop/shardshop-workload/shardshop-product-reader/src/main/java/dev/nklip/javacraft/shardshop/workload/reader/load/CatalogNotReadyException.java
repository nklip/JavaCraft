package dev.nklip.javacraft.shardshop.workload.reader.load;

import java.io.Serial;

/**
 * Product does not serve the complete catalog yet: an entity is missing, or product is unavailable, slow or not
 * reachable. A later resolution can succeed, for example after the seeder completes.
 */
public final class CatalogNotReadyException extends ReaderException {
    @Serial
    private static final long serialVersionUID = 1L;

    public CatalogNotReadyException(String message) {
        super(message);
    }
}
