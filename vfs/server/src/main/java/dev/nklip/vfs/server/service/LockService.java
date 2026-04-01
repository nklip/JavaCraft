package dev.nklip.vfs.server.service;

import dev.nklip.vfs.core.network.protocol.Protocol.User;
import dev.nklip.vfs.server.model.Node;
import dev.nklip.vfs.server.model.NodeLock;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * @author Lipatov Nikita
 */
@Service
public class LockService {

    private final Map<Node, NodeLock> lockMap = new ConcurrentHashMap<>();

    public boolean addNode(Node node) {
        if (lockMap.containsKey(node)) {
            return false;
        }
        lockMap.put(node, new NodeLock());
        return true;
    }

    public boolean removeNode(Node node) {
        if (!lockMap.containsKey(node)) {
            return false;
        }
        lockMap.remove(node);
        return true;
    }

    public int getLockMapSize() {
        return lockMap.size();
    }

    public boolean isLocked(Node node) {
        if (lockMap.containsKey(node)) {
            return lockMap.get(node).isLocked();
        }
        return false;
    }

    public boolean isLocked(Node node, boolean recursive) {
        return (recursive) ? isRecursiveLocked(node) : isLocked(node);
    }

    private boolean isRecursiveLocked(Node node) {
        if (lockMap.containsKey(node)) {
            Collection<Node> children = node.getChildren();
            for(Node child : children) {
                if(isRecursiveLocked(child)) {
                    return true;
                }
            }
            return lockMap.get(node).isLocked();
        }
        return false;
    }

    public Collection<Node> getAllLockedNodes(Node node) {
        Collection<Node> lockedNodes = new ArrayList<>();
        if(lockMap.containsKey(node)) {
            Collection<Node> children = node.getChildren();
            for(Node child : children) {
                if (isLocked(child)) {
                    lockedNodes.add(child);
                }
                lockedNodes.addAll(getAllLockedNodes(child));
            }
        }
        return lockedNodes;
    }

    public boolean lock(User user, Node node) {
        if (lockMap.containsKey(node)) {
            lockMap.get(node).lock(user);
            return true;
        }
        return false;
    }

    public boolean lock(User user, Node node, boolean recursive) {
        return (recursive) ? recursiveLock(user, node) : lock(user, node);
    }

    private boolean recursiveLock(User user, Node node) {
        if (lockMap.containsKey(node)) {
            Collection<Node> children = node.getChildren();
            for (Node child : children) {
                recursiveLock(user, child);
            }
            lockMap.get(node).lock(user);
            return true;
        }
        return false;
    }

    public boolean unlock(User user, Node node) {
        if (lockMap.containsKey(node) && isLocked(node)) {
            NodeLock nodeLock = lockMap.get(node);
            if (nodeLock.getUser().equals(user)) {
                nodeLock.unlock();
                return true;
            }
        }
        return false;
    }

    public void unlockAll(User user) {
        Set<Node> nodes = lockMap.keySet();
        for(Node node : nodes) {
            NodeLock nodeLock = lockMap.get(node);
            if(nodeLock.getUser() != null && nodeLock.getUser().equals(user)) {
                nodeLock.unlock();
            }
        }
    }

    public boolean unlock(User user, Node node, boolean recursive) {
        return (recursive) ? recursiveUnlock(user, node) : unlock(user, node);
    }

    private boolean recursiveUnlock(User user, Node node) {
        if (lockMap.containsKey(node) && isLocked(node)) {
            Collection<Node> children = node.getChildren();
            for (Node child : children) {
                recursiveUnlock(user, child);
            }
            NodeLock nodeLock = lockMap.get(node);
            if (nodeLock.getUser().equals(user)) {
                nodeLock.unlock();
                return true;
            }
        }
        return false;
    }

    public User getUser(Node node) {
        return Optional.ofNullable(node)
                .filter(lockMap::containsKey)
                .map(lockMap::get)
                .map(NodeLock::getUser)
                .orElse(null);
    }


}
