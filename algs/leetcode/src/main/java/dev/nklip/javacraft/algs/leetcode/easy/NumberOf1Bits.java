package dev.nklip.javacraft.algs.leetcode.easy;

/*
 * 191. Number of 1 Bits
 *
 * Given a positive integer n, write a function that returns
 * the number of set bits in its binary representation (also known as the Hamming weight).
 *
 */
public class NumberOf1Bits {

    public int hammingWeight(int n) {
        String binaryString = Integer.toBinaryString(n);

        int result = 0;
        for (char ch : binaryString.toCharArray()) {
            if (ch == '1') {
                result++;
            }
        }
        return result;
    }

}
