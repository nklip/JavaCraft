package dev.nklip.javacraft.shardshop.order;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

@QuarkusMain
public class OrderApplication implements QuarkusApplication {

    @Override
    public int run(String... args) {
        if (args.length == 1 && "--check-startup".equals(args[0])) {
            return 0;
        }
        Quarkus.waitForExit();
        return 0;
    }
}
