package dev.nklip.javacraft.shardshop.product;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

@QuarkusMain
public class ProductApplication implements QuarkusApplication {

    @Override
    public int run(String... args) {
        if (args.length == 1 && "--check-startup".equals(args[0])) {
            return 0;
        }
        Quarkus.waitForExit();
        return 0;
    }
}
