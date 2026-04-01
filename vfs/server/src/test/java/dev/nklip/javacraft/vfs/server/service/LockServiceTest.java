package dev.nklip.javacraft.vfs.server.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import dev.nklip.javacraft.vfs.core.network.protocol.Protocol.User;
import dev.nklip.javacraft.vfs.server.Application;
import dev.nklip.javacraft.vfs.server.model.Node;
import dev.nklip.javacraft.vfs.server.model.NodeTypes;
import dev.nklip.javacraft.vfs.server.util.NodePrinter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Collection;

/**
 * @author Lipatov Nikita
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = Application.class)
@ActiveProfiles(value = "test")
@TestMethodOrder(MethodOrderer.MethodName.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
public class LockServiceTest {

    @Autowired
    private LockService lockService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private NodePrinter nodePrinter;

    @BeforeEach
    public void setUp() {
        nodeService.initDirs();
    }

    @Test
    public void testAddNode() {
        Assertions.assertTrue(true, "This test is checked the impossibility of double adding into LockService");

        Node home = nodeService.getHome();
        Node servers = new Node("servers", NodeTypes.DIR);
        nodeService.getNodeManager().setParent(servers, home);

        Assertions.assertTrue(lockService.addNode(servers));
        Assertions.assertFalse(lockService.addNode(servers));
    }

    @Test
    public void testIsLock() {
        Node home = nodeService.getHome();
        Node servers = nodeService.getNodeManager().newNode("servers", NodeTypes.DIR);
        nodeService.getNodeManager().setParent(servers, home);
        Node weblogic = nodeService.getNodeManager().newNode("weblogic", NodeTypes.DIR);
        nodeService.getNodeManager().setParent(weblogic, servers);

        User user1 = User.newBuilder()
                .setId("121")
                .setLogin("r1d1")
                .build();

        Assertions.assertTrue(lockService.lock(user1, servers));
        Assertions.assertTrue(lockService.isLocked(servers));
        Assertions.assertFalse(lockService.isLocked(home));

        Assertions.assertEquals(
                """
                /
                |__home
                |  |__servers [Locked by r1d1 ]
                |  |  |__weblogic
                """,
                nodePrinter.print(nodeService.getRoot())
        );

        lockService.unlock(user1, servers);

        Assertions.assertEquals(
                """
                /
                |__home
                |  |__servers
                |  |  |__weblogic
                """,
                nodePrinter.print(nodeService.getRoot())
        );

    }

    @Test
    public void testRemoveNode() {
        Node node = new Node("/", NodeTypes.DIR);
        Node home = new Node("home", NodeTypes.DIR);
        home.setParent(node);

        Assertions.assertFalse(lockService.removeNode(home));
        Assertions.assertTrue(lockService.addNode(home));
        Assertions.assertTrue(lockService.removeNode(home));
        Assertions.assertFalse(lockService.removeNode(home));
    }

    @Test
    public void testRemoveNodes() {
        Node home = nodeService.getHome();
        Node applications = nodeService.getNodeManager().newNode("applications", NodeTypes.DIR);
        nodeService.getNodeManager().setParent(applications, home);
        Node weblogic     = nodeService.getNodeManager().newNode("weblogic",     NodeTypes.DIR);
        nodeService.getNodeManager().setParent(weblogic, applications);
        Node oracle       = nodeService.getNodeManager().newNode("oracle",       NodeTypes.DIR);
        nodeService.getNodeManager().setParent(oracle, weblogic);
        Node logs = nodeService.getNodeManager().newNode("logs", NodeTypes.DIR);
        nodeService.getNodeManager().setParent(logs, home);
        Node clone = nodeService.clone(applications);
        nodeService.getNodeManager().setParent(clone, logs);
        nodeService.removeNode(home, "applications");

        // 6 node should exist
        Assertions.assertEquals(6, lockService.getLockMapSize());
    }

    @Test
    public void testLock() {
        Node home = nodeService.getHome();
        Node servers = nodeService.getNodeManager().newNode("servers", NodeTypes.DIR);
        nodeService.getNodeManager().setParent(servers, home);
        Node weblogic = nodeService.getNodeManager().newNode("weblogic", NodeTypes.DIR);
        nodeService.getNodeManager().setParent(weblogic, servers);

        User user1 = User.newBuilder()
                .setId("121")
                .setLogin("nikita")
                .build();

        Assertions.assertTrue(lockService.lock(user1, home));

        Assertions.assertEquals(
                """
                /
                |__home [Locked by nikita ]
                |  |__servers
                |  |  |__weblogic
                """,
                nodePrinter.print(nodeService.getRoot())
        );

        Assertions.assertTrue(lockService.isLocked(home));
        Assertions.assertEquals(user1, lockService.getUser(home));

        lockService.unlock(user1, home);
        Assertions.assertEquals(
                """
                /
                |__home
                |  |__servers
                |  |  |__weblogic
                """,
                nodePrinter.print(nodeService.getRoot())
        );
    }

    @Test
    public void testLockMultithreading() {
        final Node home = nodeService.getHome();
        final Node servers = nodeService.getNodeManager().newNode("servers", NodeTypes.DIR);
        nodeService.getNodeManager().setParent(servers, home);
        final Node weblogic = nodeService.getNodeManager().newNode("weblogic", NodeTypes.DIR);
        nodeService.getNodeManager().setParent(weblogic, servers);

        int threads = 100;

        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            final Map<Integer, User> users = new ConcurrentHashMap<>();

            for (final AtomicInteger aint = new AtomicInteger(1); aint.get() <= threads; aint.incrementAndGet()) {
                users.put(aint.get(), User.newBuilder().setId(aint.toString()).setLogin("nikita" + aint).build());
                Runnable thread = () -> {
                    User user = users.get(aint.get());
                    if (!lockService.isLocked(weblogic)) {
                        lockService.lock(user, weblogic);
                    }
                };
                executor.execute(thread);
            }
        }

        Assertions.assertTrue(lockService.isLocked(weblogic));

        // could be any user
        Assertions.assertTrue(lockService.getUser(weblogic).getLogin().startsWith("nikita"));
        String actualResult = nodePrinter.print(nodeService.getRoot());
        Assertions.assertTrue(
                actualResult.startsWith(
                        """
                        /
                        |__home
                        |  |__servers
                        |  |  |__weblogic [Locked by nikita2 ]
                        """
                ),
                actualResult
        );
    }

    @Test
    public void testUnlock() {
        final Node home = nodeService.getHome();
        Node servers = nodeService.getNodeManager().newNode("servers", NodeTypes.DIR);
        nodeService.getNodeManager().setParent(servers, home);
        Node weblogic = nodeService.getNodeManager().newNode("weblogic", NodeTypes.DIR);
        nodeService.getNodeManager().setParent(weblogic, servers);


        User user1 = User.newBuilder()
                .setId("121")
                .setLogin("nikita")
                .build();
        User user2 = User.newBuilder()
                .setId("122")
                .setLogin("admin")
                .build();

        Assertions.assertTrue(lockService.lock(user1, home));
        Assertions.assertTrue(lockService.isLocked(home));

        Assertions.assertEquals(
                """
                /
                |__home [Locked by nikita ]
                |  |__servers
                |  |  |__weblogic
                """,
                nodePrinter.print(nodeService.getRoot())
        );

        Assertions.assertEquals(user1, lockService.getUser(home));

        Assertions.assertFalse(lockService.unlock(user2, home));
        Assertions.assertTrue(lockService.unlock(user1, home));

        Assertions.assertEquals(
                """
                /
                |__home
                |  |__servers
                |  |  |__weblogic
                """,
                nodePrinter.print(nodeService.getRoot())
        );
    }

    @Test
    public void testRecursiveLockUnlockAndUnlockAll() {
        Node home = nodeService.getHome();
        Node services = nodeService.getNodeManager().newNode("services", NodeTypes.DIR);
        nodeService.getNodeManager().setParent(services, home);
        Node auth = nodeService.getNodeManager().newNode("auth", NodeTypes.DIR);
        nodeService.getNodeManager().setParent(auth, services);

        User owner = User.newBuilder()
                .setId("501")
                .setLogin("owner")
                .build();
        User stranger = User.newBuilder()
                .setId("502")
                .setLogin("stranger")
                .build();

        Assertions.assertTrue(lockService.lock(owner, services, true));
        Assertions.assertTrue(lockService.isLocked(services));
        Assertions.assertTrue(lockService.isLocked(auth));
        Assertions.assertTrue(lockService.isLocked(home, true));

        Collection<Node> lockedNodes = lockService.getAllLockedNodes(home);
        Assertions.assertTrue(lockedNodes.contains(services));
        Assertions.assertTrue(lockedNodes.contains(auth));

        Assertions.assertFalse(lockService.unlock(stranger, services, true));
        Assertions.assertTrue(lockService.isLocked(services));
        Assertions.assertTrue(lockService.isLocked(auth));

        lockService.unlockAll(owner);
        Assertions.assertFalse(lockService.isLocked(services));
        Assertions.assertFalse(lockService.isLocked(auth));
    }

    @Test
    public void testUnknownNodeDefaultsWithMockito() {
        Node unknownNode = Mockito.mock(Node.class);
        User user = User.newBuilder()
                .setId("601")
                .setLogin("tester")
                .build();

        Assertions.assertFalse(lockService.lock(user, unknownNode));
        Assertions.assertFalse(lockService.lock(user, unknownNode, true));
        Assertions.assertFalse(lockService.unlock(user, unknownNode));
        Assertions.assertFalse(lockService.isLocked(unknownNode));
        Assertions.assertFalse(lockService.isLocked(unknownNode, true));
        Assertions.assertNull(lockService.getUser(unknownNode));
        Assertions.assertTrue(lockService.getAllLockedNodes(unknownNode).isEmpty());
    }

    @Test
    public void testRecursiveUnlockByOwnerReturnsTrue() {
        Node home = nodeService.getHome();
        Node services = nodeService.getNodeManager().newNode("services-unlock", NodeTypes.DIR);
        nodeService.getNodeManager().setParent(services, home);
        Node auth = nodeService.getNodeManager().newNode("auth-unlock", NodeTypes.DIR);
        nodeService.getNodeManager().setParent(auth, services);

        User owner = User.newBuilder()
                .setId("701")
                .setLogin("recursive-owner")
                .build();

        Assertions.assertTrue(lockService.lock(owner, services, true));
        Assertions.assertTrue(lockService.isLocked(services));
        Assertions.assertTrue(lockService.isLocked(auth));

        Assertions.assertTrue(lockService.unlock(owner, services, true));
        Assertions.assertFalse(lockService.isLocked(services));
        Assertions.assertFalse(lockService.isLocked(auth));
    }


}
