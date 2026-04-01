package dev.nklip.javacraft.algs.leetcode.easy;

import java.util.Arrays;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class MajorityElementTest {

    @Test
    void testCase1() {
        int[] array = {3,2,3};
        int[] array2 = Arrays.copyOf(array, array.length);

        MajorityElement solution = new MajorityElement();
        Assertions.assertEquals(3, solution.majorityElement(array));
        Assertions.assertEquals(3, solution.majorityElementMoore(array2));
    }

    @Test
    void testCase2() {
        int[] array = {2,2,1,1,1,2,2};
        int[] array2 = Arrays.copyOf(array, array.length);

        MajorityElement solution = new MajorityElement();
        Assertions.assertEquals(2, solution.majorityElement(array));
        Assertions.assertEquals(2, solution.majorityElementMoore(array2));
    }

    @Test
    void testCase3() {
        int[] array = {2,2};
        int[] array2 = Arrays.copyOf(array, array.length);

        MajorityElement solution = new MajorityElement();
        Assertions.assertEquals(2, solution.majorityElement(array));
        Assertions.assertEquals(2, solution.majorityElementMoore(array2));
    }
}
