package dev.nklip.javacraft.shardshop.workload.reader;

import dev.nklip.javacraft.shardshop.workload.reader.load.ProductReader;
import dev.nklip.javacraft.shardshop.workload.reader.load.ReaderException;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Objects;

/**
 * Long-running Deployment entry point: it reads until the pod stops. Exit code 1 means that the catalog resolution
 * or a worker failed.
 */
@QuarkusMain
public class ProductReaderApplication implements QuarkusApplication {
    private static final Logger LOG = Logger.getLogger(ProductReaderApplication.class);

    private final ProductReader reader;
    private final Runnable waitForExit;
    private final Runnable exitAfterFailure;

    @Inject
    public ProductReaderApplication(ProductReader reader) {
        this(reader, Quarkus::waitForExit, () -> Quarkus.asyncExit(1));
    }

    ProductReaderApplication(ProductReader reader, Runnable waitForExit, Runnable exitAfterFailure) {
        this.reader = Objects.requireNonNull(reader);
        this.waitForExit = Objects.requireNonNull(waitForExit);
        this.exitAfterFailure = Objects.requireNonNull(exitAfterFailure);
    }

    @Override
    public int run(String... args) {
        if (args.length == 1 && "--check-startup".equals(args[0])) {
            return 0;
        }
        try {
            reader.start(exitAfterFailure);
            waitForExit.run();
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
