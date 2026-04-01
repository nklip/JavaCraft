package dev.nklip.javacraft.algs.leetcode.easy;

/**
 * 69. Sqrt(x)
 * <p>
 * Given a non-negative integer x, return the square root of x rounded down to the nearest integer.
 * The returned integer should be non-negative as well.
 * <p>
 * You must not use any built-in exponent function or operator.
 * <p>
 * For example, do not use pow(x, 0.5) in c++ or x ** 0.5 in python.
 */
public class Sqrt {

    // looks ugly, but it bets 97.28% in Runtime and 91.25% in Memory
    public int mySqrt(int x) {
        // to find an initial seed
        double seed;
        if (x < 100) {
            seed = 2;
        } else if (x < 1000) {
            seed = 10;
        } else if (x < 10000) {
            seed = 50;
        } else if (x < 100000) {
            seed = 200;
        } else if (x < 1000000) {
            seed = 1000;
        } else {
            seed = 50000;
        }
        // no more than 15 iterations
        int iterations = 15;
        int precision = 10 * (-10);
        while (Math.abs(x - seed * seed) > precision) {
            // Babylonian Method
            seed = (seed + (x / seed)) / 2;

            iterations--;
            if (iterations < 0) {
                break;
            }
        }
        return (int)seed;
    }

}
