package dev.nklip.javacraft.algs.leetcode.easy;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ExcelSheetColumnTitleTest {

    @Test
    void testCases() {
        ExcelSheetColumnTitle solution = new ExcelSheetColumnTitle();

        Assertions.assertEquals("A", solution.convertToTitle(1));
        Assertions.assertEquals("B", solution.convertToTitle(2));
        Assertions.assertEquals("C", solution.convertToTitle(3));
        // 1 -> 2
        Assertions.assertEquals("Z", solution.convertToTitle(26));
        Assertions.assertEquals("AA", solution.convertToTitle(27));
        Assertions.assertEquals("AB", solution.convertToTitle(28));
        // 2 -> 3
        Assertions.assertEquals("ZZ", solution.convertToTitle(702));
        Assertions.assertEquals("AAA", solution.convertToTitle(703));
        Assertions.assertEquals("AAB", solution.convertToTitle(704));
        // 3 -> 4
        Assertions.assertEquals("ZZZ", solution.convertToTitle(18278));
        Assertions.assertEquals("AAAA", solution.convertToTitle(18279));
        Assertions.assertEquals("AAAB", solution.convertToTitle(18280));
        // 4 -> 5
        Assertions.assertEquals("ZZZZ", solution.convertToTitle(475254));
        Assertions.assertEquals("AAAAA", solution.convertToTitle(475255));
        Assertions.assertEquals("AAAAB", solution.convertToTitle(475256));
        // 5 -> 6
        Assertions.assertEquals("ZZZZZ", solution.convertToTitle(12356630));
        Assertions.assertEquals("AAAAAA", solution.convertToTitle(12356631));
        Assertions.assertEquals("AAAAAB", solution.convertToTitle(12356632));

        // 6 -> 7
        Assertions.assertEquals("ZZZZZZ", solution.convertToTitle(321272406));
        Assertions.assertEquals("AAAAAAA", solution.convertToTitle(321272407));

        Assertions.assertEquals("FXSHRXW", solution.convertToTitle(Integer.MAX_VALUE));
    }

}
