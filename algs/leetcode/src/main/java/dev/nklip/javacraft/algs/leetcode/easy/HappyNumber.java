package dev.nklip.javacraft.algs.leetcode.easy;

import java.util.HashSet;
import java.util.Set;

/*
 * 202. Happy Number
 *
 * LeetCode: https://leetcode.com/problems/happy-number/
 *
 * Write an algorithm to determine if a number n is happy.
 *
 * A happy number is a number defined by the following process:
 *
 * 1) Starting with any positive integer, replace the number by the sum of the squares of its digits.
 * 2) Repeat the process until the number equals 1 (where it will stay),
 * or it loops endlessly in a cycle which does not include 1.
 * 3) Those numbers for which this process ends in 1 are happy.
 *
 * Return true if n is a happy number, and false if not.
 */
public class HappyNumber {

    public boolean isHappy(int n) {
        String input = String.valueOf(n);

        Set<Integer> set = new HashSet<>();
        while (true) {
            int sum = 0;
            for (int i = 0; i < input.length(); i++) {
                int singleInt = input.charAt(i) - '0';
                int squareInt = singleInt * singleInt;
                sum += squareInt;
            }
            if (sum == 1) {
                return true;
            }
            if (set.contains(sum)) {
                return false;
            }
            set.add(sum);
            input = String.valueOf(sum);
        }
    }

}
