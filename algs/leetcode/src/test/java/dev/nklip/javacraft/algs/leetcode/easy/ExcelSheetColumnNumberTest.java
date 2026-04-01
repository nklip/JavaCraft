package dev.nklip.javacraft.algs.leetcode.easy;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ExcelSheetColumnNumberTest {

    @Test
    void testCases() {
        ExcelSheetColumnNumber solution = new ExcelSheetColumnNumber();

        Assertions.assertEquals(1, solution.titleToNumber("A"));
        Assertions.assertEquals(2, solution.titleToNumber("B"));
        Assertions.assertEquals(3, solution.titleToNumber("C"));
        // 1 -> 2
        Assertions.assertEquals(26, solution.titleToNumber("Z"));
        Assertions.assertEquals(27, solution.titleToNumber("AA"));
        Assertions.assertEquals(28, solution.titleToNumber("AB"));
        // 2 -> 3
        Assertions.assertEquals(702, solution.titleToNumber("ZZ"));
        Assertions.assertEquals(703, solution.titleToNumber("AAA"));
        Assertions.assertEquals(704, solution.titleToNumber("AAB"));
        // 3 -> 4
        Assertions.assertEquals(18278, solution.titleToNumber("ZZZ"));
        Assertions.assertEquals(18279, solution.titleToNumber("AAAA"));
        Assertions.assertEquals(18280, solution.titleToNumber("AAAB"));
        // 4 -> 5
        Assertions.assertEquals(475254, solution.titleToNumber("ZZZZ"));
        Assertions.assertEquals(475255, solution.titleToNumber("AAAAA"));
        Assertions.assertEquals(475256, solution.titleToNumber("AAAAB"));
        // 5 -> 6
        Assertions.assertEquals(12356630, solution.titleToNumber("ZZZZZ"));
        Assertions.assertEquals(12356631, solution.titleToNumber("AAAAAA"));
        Assertions.assertEquals(12356632, solution.titleToNumber("AAAAAB"));

        // 6 -> 7
        Assertions.assertEquals(321272406, solution.titleToNumber("ZZZZZZ"));
        Assertions.assertEquals(321272407, solution.titleToNumber("AAAAAAA"));

        Assertions.assertEquals(Integer.MAX_VALUE, solution.titleToNumber("FXSHRXW"));
    }

}
