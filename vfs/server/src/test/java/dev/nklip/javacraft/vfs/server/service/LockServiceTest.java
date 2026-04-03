package dev.nklip.javacraft.vfs.server.service;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

        final int threads = 100;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            for (int index = 1; index <= threads; index++) {
                final User user = User.newBuilder()
                        .setId(Integer.toString(index))
                        .setLogin("nikita" + index)
                        .build();
                Runnable thread = () -> {
                    ready.countDown();
                    try {
                        Assertions.assertTrue(start.await(5, TimeUnit.SECONDS));
                        if (!lockService.isLocked(weblogic)) {
                            lockService.lock(user, weblogic);
                        }
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        Assertions.fail(interruptedException);
                    } finally {
                        done.countDown();
                    }
                };
                executor.execute(thread);
            }

            Assertions.assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            Assertions.assertTrue(done.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            Assertions.fail(interruptedException);
        }

        Assertions.assertTrue(lockService.isLocked(weblogic));

        User lockingUser = lockService.getUser(weblogic);
        Assertions.assertNotNull(lockingUser);
        Assertions.assertTrue(lockingUser.getLogin().startsWith("nikita"));
        String actualResult = nodePrinter.print(nodeService.getRoot());
        String expectedResult = """
                /
                |__home
                |  |__servers
                |  |  |__weblogic [Locked by %s ]
                """.formatted(lockingUser.getLogin());
        Assertions.assertEquals(
                expectedResult,
                actualResult
        );
    }

    @Test
    public void testLockMultithreadingWhenSpecificUserLocksFirst() {
        final Node home = nodeService.getHome();
        final Node servers = nodeService.getNodeManager().newNode("servers-first-owner", NodeTypes.DIR);
        nodeService.getNodeManager().setParent(servers, home);
        final Node weblogic = nodeService.getNodeManager().newNode("weblogic-first-owner", NodeTypes.DIR);
        nodeService.getNodeManager().setParent(weblogic, servers);

        User winningUser = User.newBuilder()
                .setId("1")
                .setLogin("nikita-1")
                .build();

        Assertions.assertTrue(lockService.lock(winningUser, weblogic));

        final int threads = 50;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            for (int index = 2; index <= threads + 1; index++) {
                final User challenger = User.newBuilder()
                        .setId(Integer.toString(index))
                        .setLogin("nikita-" + index)
                        .build();
                executor.execute(() -> {
                    ready.countDown();
                    try {
                        Assertions.assertTrue(start.await(5, TimeUnit.SECONDS));
                        lockService.lock(challenger, weblogic);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        Assertions.fail(interruptedException);
                    } finally {
                        done.countDown();
                    }
                });
            }

            Assertions.assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            Assertions.assertTrue(done.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            Assertions.fail(interruptedException);
        }

        Assertions.assertTrue(lockService.isLocked(weblogic));
        Assertions.assertEquals(winningUser, lockService.getUser(weblogic));
        Assertions.assertEquals(
                """
                /
                |__home
                |  |__servers-first-owner
                |  |  |__weblogic-first-owner [Locked by nikita-1 ]
                """,
                nodePrinter.print(nodeService.getRoot())
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
