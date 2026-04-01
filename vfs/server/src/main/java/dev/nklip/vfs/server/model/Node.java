package dev.nklip.vfs.server.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * @author Lipatov
 * date 11.02.14
 */
public class Node implements Comparable<Node> {

    @Setter
    @Getter
    protected NodeTypes type;
    @Getter
    protected Node parent;
    @Setter
    @Getter
    protected String name;

    protected List<Node> children = new ArrayList<>();

    public Node(String name, NodeTypes type) {
        this.name = name;
        this.type = type;
    }

    public void setParent(Node parent) {
        if (parent == null) {
            this.parent = parent;
            return;
        }
        if (parent.getType() != NodeTypes.DIR) {
            throw new IllegalArgumentException("Node has different type than NodeTypes.DIR");
        }
        this.parent = parent;
        parent.children.add(this);
    }

    public Collection<Node> getChildren() {
        return Collections.unmodifiableCollection(children);
    }

    public boolean contains(Node node) {
        return children.contains(node);
    }

    public boolean removeChild(Node node) {
        return children.remove(node);
    }

    public int compareTo(Node node) {
        return name.compareTo(node.getName());
    }

}
