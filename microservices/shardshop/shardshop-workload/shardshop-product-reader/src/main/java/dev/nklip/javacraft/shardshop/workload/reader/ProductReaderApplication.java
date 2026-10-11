package dev.nklip.javacraft.shardshop.workload.reader;

import dev.nklip.javacraft.shardshop.workload.reader.config.CatalogWait;
import dev.nklip.javacraft.shardshop.workload.reader.load.CatalogNotReadyException;
import dev.nklip.javacraft.shardshop.workload.reader.load.ProductReader;
import dev.nklip.javacraft.shardshop.workload.reader.load.ReaderException;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Objects;

/**
 * Long-running Deployment entry point. It waits until product serves the complete catalog, then reads until the pod
 * stops. Exit code 1 means that the catalog was not ready within the wait, or that the resolution or a worker failed.
 */
@QuarkusMain
public class ProductReaderApplication implements QuarkusApplication {
    private static final Logger LOG = Logger.getLogger(ProductReaderApplication.class);

    private final ProductReader reader;
    private final CatalogWait catalogWait;
    private final Runnable waitForExit;
    private final Runnable exitAfterFailure;

    @Inject
    public ProductReaderApplication(ProductReader reader, CatalogWait catalogWait) {
        this(reader, catalogWait, Quarkus::waitForExit, () -> Quarkus.asyncExit(1));
    }

    ProductReaderApplication(ProductReader reader, CatalogWait catalogWait, Runnable waitForExit,
                             Runnable exitAfterFailure) {
        this.reader = Objects.requireNonNull(reader);
        this.catalogWait = Objects.requireNonNull(catalogWait);
        this.waitForExit = Objects.requireNonNull(waitForExit);
        this.exitAfterFailure = Objects.requireNonNull(exitAfterFailure);
    }

    @Override
    public int run(String... args) {
        if (args.length == 1 && "--check-startup".equals(args[0])) {
            return 0;
        }
        try {
            reader.start(catalogWait.resolve(reader), exitAfterFailure);
            waitForExit.run();
        } catch (CatalogNotReadyException failure) {
            LOG.error("Product reader startup failed: the catalog was not complete within the wait. Last failure: "
                    + failure.getMessage());
            return 1;
        } catch (ReaderException failure) {
            LOG.error("Product reader startup failed: " + failure.getMessage());
            return 1;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            LOG.error("Product reader startup was interrupted");
            return 1;
        } finally {
            reader.stop();
        }
        return reader.failed() ? 1 : 0;
    }
}
