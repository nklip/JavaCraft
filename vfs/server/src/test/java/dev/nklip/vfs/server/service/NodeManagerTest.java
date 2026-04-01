package dev.nklip.vfs.server.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import dev.nklip.vfs.server.model.Node;
import dev.nklip.vfs.server.model.NodeTypes;

class NodeManagerTest {

    @Test
    void testSetParentAddsChildAndSetsParent() {
        LockService lockService = Mockito.mock(LockService.class);
        NodeManager nodeManager = new NodeManager(lockService);

        Node parent = new Node("parent", NodeTypes.DIR);
        Node child = new Node("child", NodeTypes.DIR);

        nodeManager.setParent(child, parent);

        Assertions.assertSame(parent, child.getParent());
        Assertions.assertTrue(parent.contains(child));
    }

    @Test
    void testSetParentWithNullParent() {
        LockService lockService = Mockito.mock(LockService.class);
        NodeManager nodeManager = new NodeManager(lockService);

        Node child = new Node("child", NodeTypes.DIR);

        nodeManager.setParent(child, null);

        Assertions.assertNull(child.getParent());
    }

    @Test
    void testSetParentThrowsWhenDuplicateNameExists() {
        LockService lockService = Mockito.mock(LockService.class);
        NodeManager nodeManager = new NodeManager(lockService);

        Node parent = new Node("root", NodeTypes.DIR);
        Node existing = new Node("dup", NodeTypes.DIR);
        existing.setParent(parent);
        Node duplicate = new Node("dup", NodeTypes.DIR);

        IllegalArgumentException thrown = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> nodeManager.setParent(duplicate, parent)
        );

        Assertions.assertEquals("Name already exist for root", thrown.getMessage());
    }

    @Test
    void testNewNodeAddsNodeToLockService() {
        LockService lockService = Mockito.mock(LockService.class);
        NodeManager nodeManager = new NodeManager(lockService);

        Node node = nodeManager.newNode("notes.txt", NodeTypes.FILE);

        Assertions.assertEquals("notes.txt", node.getName());
        Assertions.assertEquals(NodeTypes.FILE, node.getType());
        Mockito.verify(lockService).addNode(node);
    }

    @Test
    void testRemoveNodeSuccess() {
        LockService lockService = Mockito.mock(LockService.class);
        NodeManager nodeManager = new NodeManager(lockService);

        Node source = new Node("source", NodeTypes.DIR);
        Node child = new Node("child", NodeTypes.DIR);
        child.setParent(source);

        boolean result = nodeManager.removeNode(source, child);

        Assertions.assertTrue(result);
        Assertions.assertFalse(source.contains(child));
        Assertions.assertNull(child.getParent());
        Mockito.verify(lockService).removeNode(child);
    }

    @Test
    void testRemoveNodeReturnsFalseWhenChildMissing() {
        LockService lockService = Mockito.mock(LockService.class);
        NodeManager nodeManager = new NodeManager(lockService);

        Node source = new Node("source", NodeTypes.DIR);
        Node child = new Node("child", NodeTypes.DIR);

        boolean result = nodeManager.removeNode(source, child);

        Assertions.assertFalse(result);
        Assertions.assertNull(child.getParent());
        Mockito.verify(lockService).removeNode(child);
    }
}
