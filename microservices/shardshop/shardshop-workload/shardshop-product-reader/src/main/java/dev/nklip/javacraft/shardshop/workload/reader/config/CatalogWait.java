package dev.nklip.javacraft.shardshop.workload.reader.config;

import dev.nklip.javacraft.shardshop.workload.reader.load.CatalogNotReadyException;
import dev.nklip.javacraft.shardshop.workload.reader.load.ProductReader;
import dev.nklip.javacraft.shardshop.workload.reader.load.ReaderException;
import io.smallrye.faulttolerance.api.ExponentialBackoff;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.jboss.logging.Logger;

/**
 * The reader's start condition, which the reader owns: it sends load only after product serves the complete
 * dataset. While the catalog is not ready, SmallRye Fault Tolerance repeats the resolution with exponential backoff
 * from 1 to 15 seconds, for at most 5 minutes. Then the last failure stops startup. Other resolution failures stop
 * startup at once. The MicroProfile Fault Tolerance keys {@code <this class>/resolve/Retry/maxDuration} and
 * {@code .../Retry/delay}, and {@code .../ExponentialBackoff/maxDelay}, change the bounds in milliseconds.
 */
@Singleton
public class CatalogWait {
    private static final Logger LOG = Logger.getLogger(CatalogWait.class);

    @Retry(maxRetries = -1, delay = 1_000, maxDuration = 300_000, retryOn = CatalogNotReadyException.class)
    @ExponentialBackoff(factor = 2, maxDelay = 15_000)
    public ProductReader.Catalog resolve(ProductReader reader) throws ReaderException, InterruptedException {
        try {
            return reader.resolve();
        } catch (CatalogNotReadyException notReady) {
            LOG.info("The catalog is not complete yet: " + notReady.getMessage());
            throw notReady;
        }
    }
}
