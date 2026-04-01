package dev.nklip.vfs.server.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import dev.nklip.vfs.server.Application;
import dev.nklip.vfs.server.model.Node;
import dev.nklip.vfs.server.model.NodeTypes;
import dev.nklip.vfs.server.util.NodePrinter;
import dev.nklip.vfs.core.network.protocol.Protocol.User;

/**
 * @author Lipatov Nikita
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = Application.class)
@ActiveProfiles(value = "test")
@TestMethodOrder(MethodOrderer.MethodName.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
public class NodeServiceTest {

    @Autowired
    private NodeManager nodeManager;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private NodePrinter nodePrinter;
    @Autowired
    private LockService lockService;

    @BeforeEach
    public void setUp() {
        nodeService.initDirs();
    }

    @Test
    public void testClone() {
        Node home = this.nodeService.getHome();
        Node boy  = this.nodeManager.newNode("boy",  NodeTypes.DIR);
        Node girl = this.nodeManager.newNode("girl", NodeTypes.DIR);
        this.nodeManager.setParent(boy, home);
        this.nodeManager.setParent(girl, home);
        Node homeClone = nodeService.clone(home);

        Assertions.assertEquals(home.getChildren().size(), homeClone.getChildren().size());
        Assertions.assertNotEquals(home.toString(), homeClone.toString());
        Assertions.assertNotEquals(home.getChildren().toArray()[0].toString(), homeClone.getChildren().toArray()[0].toString());
        Assertions.assertEquals(((Node) home.getChildren().toArray()[0]).getName(), ((Node) homeClone.getChildren().toArray()[0]).getName());
        Assertions.assertNotEquals(home.getChildren().toArray()[1].toString(), homeClone.getChildren().toArray()[1].toString());
        Assertions.assertEquals(((Node) home.getChildren().toArray()[1]).getName(), ((Node) homeClone.getChildren().toArray()[1]).getName());
    }

    @Test
    public void testCreateNode() {
        nodeService.createNode(nodeService.getRoot(), "test", NodeTypes.DIR);
        String tree = nodePrinter.print(nodeService.getRoot());
        Assertions.assertEquals(
                """
                /
                |__home
                |__test
                """,
                tree);
    }

    @Test
    public void testCreateNodeStartSlash() {
        nodeService.createNode(nodeService.getRoot(), "/test", NodeTypes.DIR);
        String tree = nodePrinter.print(nodeService.getRoot());
        Assertions.assertEquals(
                """
                /
                |__home
                |__test
                """,
                tree
        );
    }

    @Test
    public void testCreateNodeThreeDirs() {
        nodeService.createNode(nodeService.getRoot(), "/test1/test2/test3", NodeTypes.DIR);
        String tree = nodePrinter.print(nodeService.getRoot());
        Assertions.assertEquals(
                """
                /
                |__home
                |__test1
                |  |__test2
                |  |  |__test3
                """,
                tree
        );
    }

    @Test
    public void testCreateNodeTwoDirsAndFile() {
        nodeService.createNode(nodeService.getRoot(), "/test1/test2/weblogic.log", NodeTypes.FILE);
        String tree = nodePrinter.print(nodeService.getRoot());
        Assertions.assertEquals(
                """
                /
                |__home
                |__test1
                |  |__test2
                |  |  |__weblogic.log
                """,
                tree
        );
    }

    @Test
    public void testCreateNodeFoldersAlreadyExist() {
        Node root = new Node("root", NodeTypes.DIR);
        Node test = new Node("logs", NodeTypes.DIR);
        Node file = new Node("weblogic.log", NodeTypes.FILE);
        file.setParent(test);
        test.setParent(root);
        root.setParent(nodeService.getRoot());

        nodeService.createNode(nodeService.getRoot(), "/root/logs/weblogic_clust1.log", NodeTypes.FILE);
        String tree = nodePrinter.print(nodeService.getRoot());
        Assertions.assertEquals(
                """
                /
                |__home
                |__root
                |  |__logs
                |  |  |__weblogic.log
                |  |  |__weblogic_clust1.log
                """,
                tree
        );
    }

    @Test
    public void testSetParentUniqueNames() {
        Node root = new Node("/", NodeTypes.DIR);
        Node home1 = new Node("home", NodeTypes.DIR);
        nodeService.getNodeManager().setParent(home1, root);
        home1.setParent(root);
        Node home2 = new Node("home", NodeTypes.DIR);

        IllegalArgumentException thrown = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> nodeService.getNodeManager().setParent(home2, root)
        );

        Assertions.assertEquals("Name already exist for /", thrown.getMessage());
    }

    @Test
    public void testConstructorRejectsInvalidSeparatorLength() {
        LockService lockServiceMock = Mockito.mock(LockService.class);
        NodeManager nodeManagerMock = Mockito.mock(NodeManager.class);

        IllegalArgumentException thrown = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new NodeService("//", lockServiceMock, nodeManagerMock)
        );

        Assertions.assertEquals("Separator should consist from one symbol!", thrown.getMessage());
    }

    @Test
    public void testRemoveDoubleSeparators() {
        String normalized = nodeService.removeDoubleSeparators("///a////b//");
        Assertions.assertEquals("/a/b/", normalized);
    }

    @Test
    public void testGetFullPath() {
        Node home = nodeService.getHome();
        Node apps = nodeService.createNode(home, "apps", NodeTypes.DIR);
        Node logs = nodeService.createNode(apps, "logs", NodeTypes.DIR);
        Node file = nodeService.createNode(logs, "run.log", NodeTypes.FILE);

        Assertions.assertEquals("/home/apps/logs/run.log", nodeService.getFullPath(file));
    }

    @Test
    public void testFindByNameSpecialPaths() {
        Node home = nodeService.getHome();

        Assertions.assertSame(home, nodeService.findByName(home, "."));
        Assertions.assertSame(home, nodeService.findByName(home, ""));
        Assertions.assertSame(nodeService.getRoot(), nodeService.findByName(home, ".."));
        Assertions.assertSame(nodeService.getRoot(), nodeService.findByName(nodeService.getRoot(), ".."));
        Assertions.assertNull(nodeService.findByName(home, "missing"));
    }

    @Test
    public void testCreateNodeThroughFileThrowsException() {
        Node root = nodeService.getRoot();
        nodeService.createNode(root, "/single-file.txt", NodeTypes.FILE);

        IllegalArgumentException thrown = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> nodeService.createNode(root, "/single-file.txt/child", NodeTypes.DIR)
        );

        Assertions.assertEquals("You try to create the node through file node!", thrown.getMessage());
    }

    @Test
    public void testCreateNodeFailsWhenParentLocked() {
        Node home = nodeService.getHome();
        User user = User.newBuilder()
                .setId("u-1")
                .setLogin("nikita")
                .build();
        lockService.lock(user, home);

        IllegalAccessError thrown = Assertions.assertThrows(
                IllegalAccessError.class,
                () -> nodeService.createNode(home, "blocked-dir", NodeTypes.DIR)
        );

        Assertions.assertTrue(thrown.getMessage().contains("Parent node /home is locked!"));
    }

    @Test
    public void testGetNodeWithNullAndFileIntermediatePath() {
        Node root = nodeService.getRoot();
        nodeService.createNode(root, "/note.txt", NodeTypes.FILE);

        Assertions.assertNull(nodeService.getNode(root, null));
        Assertions.assertEquals("note.txt", nodeService.getNode(root, "/note.txt/anything").getName());
    }

    @Test
    public void testRemoveNodeMissingPaths() {
        Node root = nodeService.getRoot();

        Assertions.assertFalse(nodeService.removeNode(root, "/missing/path"));
        Assertions.assertTrue(nodeService.removeNode(root, "missing"));
    }

    @Test
    public void testGetAllNodes() {
        Node home = nodeService.getHome();
        Node apps = nodeService.createNode(home, "apps", NodeTypes.DIR);
        nodeService.createNode(apps, "serviceA", NodeTypes.DIR);
        nodeService.createNode(apps, "serviceB", NodeTypes.DIR);

        Assertions.assertEquals(2, nodeService.getAllNodes(apps).size());
    }

    @Test
    public void testInitDirsIsIdempotent() {
        Node initialRoot = nodeService.getRoot();
        Node initialHome = nodeService.getHome();

        nodeService.initDirs();

        Assertions.assertSame(initialRoot, nodeService.getRoot());
        Assertions.assertSame(initialHome, nodeService.getHome());
        Assertions.assertEquals(1, nodeService.getRoot().getChildren().size());
    }

    @Test
    public void testCreateAndRemoveHomeDirectory() {
        nodeService.createHomeDirectory("neo");
        Assertions.assertNotNull(nodeService.findByName(nodeService.getHome(), "neo"));

        nodeService.removeHomeDirectory("neo");
        Assertions.assertNull(nodeService.findByName(nodeService.getHome(), "neo"));
    }

    @Test
    public void testRemoveHomeDirectoryWithLockedDescendant() {
        Node userHome = nodeService.createHomeDirectory("trinity");
        Node child = nodeService.createNode(userHome, "docs", NodeTypes.DIR);
        User user = User.newBuilder()
                .setId("u-2")
                .setLogin("trinity")
                .build();
        lockService.lock(user, child);

        nodeService.removeHomeDirectory("trinity");

        Assertions.assertNull(nodeService.findByName(nodeService.getHome(), "trinity"));
    }

    @Test
    public void testCoverageForRequestedNodeServiceLines() {
        Node root = nodeService.getRoot();

        Node created = nodeService.createNode(root, "/alpha/beta", NodeTypes.DIR);
        Assertions.assertEquals("/alpha/beta", nodeService.getFullPath(created));
        Assertions.assertSame(root, nodeService.findByName(root, ".."));
        Assertions.assertNull(nodeService.getNode(root, "/missing/beta"));
    }

    @Test
    public void testCreateAndRemoveHomeDirectoryExplicitly() {
        Node home = nodeService.getHome();

        Node loginHome = nodeService.createHomeDirectory("morpheus");
        Assertions.assertEquals("morpheus", loginHome.getName());
        Assertions.assertSame(home, loginHome.getParent());
        Assertions.assertNotNull(nodeService.findByName(home, "morpheus"));

        nodeService.removeHomeDirectory("morpheus");
        Assertions.assertNull(nodeService.findByName(home, "morpheus"));
        nodeService.removeHomeDirectory("unknown-user");
        Assertions.assertNull(nodeService.findByName(home, "unknown-user"));
    }

    @Test
    public void testRemoveNodeRecursiveBranch() {
        Node root = nodeService.getRoot();
        nodeService.createNode(root, "/remove-me/child", NodeTypes.DIR);

        Assertions.assertTrue(nodeService.removeNode(root, "/remove-me/child"));
        Assertions.assertNull(nodeService.getNode(root, "/remove-me/child"));
    }
}
