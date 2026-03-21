package my.javacraft.algs.leetcode.easy;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class MissingNumberTest {

    @Test
    void testCase1() {
        int[] nums = {3, 0, 1};

        MissingNumber solution =  new MissingNumber();

        // n = 3 since there are 3 numbers, so all numbers are in the range [0,3].
        // 2 is the missing number in the range since it does not appear in nums.
        Assertions.assertEquals(2, solution.missingNumber(nums));
    }

    @Test
    void testCase2() {
        int[] nums = {0, 1};

        MissingNumber solution =  new MissingNumber();

        // n = 2 since there are 2 numbers, so all numbers are in the range [0,2].
        // 2 is the missing number in the range since it does not appear in nums.
        Assertions.assertEquals(2, solution.missingNumber(nums));
    }

    @Test
    void testCase3() {
        int[] nums = {9, 6, 4, 2, 3, 5, 7, 0, 1};

        MissingNumber solution =  new MissingNumber();

        // n = 9 since there are 9 numbers, so all numbers are in the range [0,9].
        // 8 is the missing number in the range since it does not appear in nums.
        Assertions.assertEquals(8, solution.missingNumber(nums));
    }

}
