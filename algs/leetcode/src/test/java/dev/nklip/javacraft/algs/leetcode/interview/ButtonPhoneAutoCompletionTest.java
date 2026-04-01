package dev.nklip.javacraft.algs.leetcode.interview;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ButtonPhoneAutoCompletionTest {

    @Test
    void testCase1() {
        ButtonPhoneAutoCompletion solution = new ButtonPhoneAutoCompletion();

        List<String> input = new ArrayList<>();
        input.add("rise");
        input.add("home");
        input.add("exit");
        input.add("list");
        input.add("nine");
        input.add("game");
        input.add("cool");
        input.add("calm");
        input.add("tree");
        input.add("face");

        Assertions.assertEquals("home", solution.findMatchingWord(4663, input));
        Assertions.assertEquals("", solution.findMatchingWord(2120, input));
        Assertions.assertEquals("", solution.findMatchingWord(5555, input));
    }

}
