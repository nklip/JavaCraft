package dev.nklip.javacraft.algs.leetcode.easy;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/*
 * 70. Climbing Stairs
 *
 * LeetCode: https://leetcode.com/problems/climbing-stairs/
 *
 * You are climbing a staircase. It takes n steps to reach the top.
 *
 * Each time you can either climb 1 or 2 steps.
 * In how many distinct ways can you climb to the top?
 */
public class ClimbingStairs {

    // effectively we need to find all combinations of 1 and 2 to get n

    // ----------------------
    // The number of ways to write n as a sum of 1s and 2s (order matters) is:
    // F(n)+1
    // Where F is the Fibonacci sequence.
    // ----------------------
    public int climbStairs(int n) {
        if (n == 1) {
            return 1;
        }
        if (n == 2) {
            return 2;
        }

        int[] a = new int[n];
        a[0] = 1;
        a[1] = 2;

        for (int i = 2; i < n; i++) {
            a[i] = a[i-1] + a[i-2];
        }
        return a[n-1];
    }

    public int climbStairs2(int n) {
        Map<Integer, BigInteger> fibonacci = new LinkedHashMap<>();
        fibonacci.put(1, BigInteger.ONE);
        fibonacci.put(2, BigInteger.TWO);

        BigInteger val1 = BigInteger.ONE;
        BigInteger val2 = BigInteger.TWO;

        for (int i = 3; i <= n; i++) {
            BigInteger sumValue = val1.add(val2);
            fibonacci.put(i, sumValue);

            val1 = val2;
            val2 = sumValue;
        }

        return fibonacci.get(n).intValue();
    }



}
