package dev.nklip.javacraft.algs.leetcode.easy;

import java.util.LinkedList;

/*
 * 225. Implement Stack using Queues
 *
 * LeetCode: https://leetcode.com/problems/implement-stack-using-queues/
 *
 * Implement a last-in-first-out (LIFO) stack using only two queues.
 * The implemented stack should support all the functions of a normal stack (push, top, pop, and empty).
 *
 * Implement the MyStack class:
 *
 * 1) void push(int x) Pushes element x to the top of the stack.
 * 2) int pop() Removes the element on the top of the stack and returns it.
 * 3) int top() Returns the element on the top of the stack.
 * 4) boolean empty() Returns true if the stack is empty, false otherwise.
 *
 * Notes:
 *
 * 1) You must use only standard operations of a queue, which means that only push to back, p
 * eek/pop from front, size and is empty operations are valid.
 *
 * 2) Depending on your language, the queue may not be supported natively.
 * You may simulate a queue using a list or deque (double-ended queue) as long as you use only
 * a queue's standard operations.
 */
public class ImplementStackUsingQueues<T> {

    private final LinkedList<T> queue = new LinkedList<>();

    public void push(T x) {
        queue.add(x);
    }

    public T pop() {
        return queue.removeLast();
    }

    public T top() {
        return queue.getLast();
    }

    public boolean empty() {
        return queue.isEmpty();
    }

}
