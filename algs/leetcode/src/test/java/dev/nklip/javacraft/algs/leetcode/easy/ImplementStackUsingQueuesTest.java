package dev.nklip.javacraft.algs.leetcode.easy;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ImplementStackUsingQueuesTest {

    @Test
    void testCase1() {
        ImplementStackUsingQueues<Integer> myStack = new ImplementStackUsingQueues<>();

        Assertions.assertTrue(myStack.empty());

        myStack.push(1);
        Assertions.assertEquals(1, myStack.top());
        Assertions.assertFalse(myStack.empty());

        myStack.push(2);
        Assertions.assertEquals(2, myStack.top());
        Assertions.assertFalse(myStack.empty());

        Assertions.assertEquals(2, myStack.pop());

        Assertions.assertEquals(1, myStack.top());
        Assertions.assertFalse(myStack.empty());
    }

}
