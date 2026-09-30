package dev.nklip.javacraft.shardshop.order;

import dev.nklip.javacraft.shardshop.sharding.Shard;
import dev.nklip.javacraft.shardshop.sharding.ShardRouter;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "shardshop.routing.config=classpath:routing-test.properties")
class OrderApplicationTest {

    private final ConfigurableApplicationContext context;

    @Autowired
    OrderApplicationTest(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @Test
    void mainStartsApplicationWithoutExternalServices() {
        assertTrue(context.isActive());
        assertNotNull(context.getBean(OrderApplication.class));
        ShardTopology topology = context.getBean(ShardTopology.class);
        assertEquals(List.of(new Shard("shard-b"), new Shard("shard-a")), topology.shards());
        assertEquals("shard-a", context.getBean(ShardRouter.class).route(1L).clusterName());
    }
}
