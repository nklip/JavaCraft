package dev.nklip.javacraft.algs.leetcode.easy;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class BestTimeToBuyAndSellStockTest {


    @Test
    void testCase1() {
        int[] prices = {7,1,5,3,6,4};

        BestTimeToBuyAndSellStock solution = new BestTimeToBuyAndSellStock();

        // Explanation: Buy on day 2 (price = 1) and sell on day 5 (price = 6), profit = 6-1 = 5.
        // Note that buying on day 2 and selling on day 1 is not allowed because you must buy before you sell.
        Assertions.assertEquals(5, solution.maxProfit(prices));
    }

    @Test
    void testCase2() {
        int[] prices = {7,6,4,3,1};

        BestTimeToBuyAndSellStock solution = new BestTimeToBuyAndSellStock();

        // A bear market is a financial market condition where prices of assets (usually stocks)
        // are falling for a sustained period, and investor confidence is negative.
        // So no transactions are done and the max profit = 0.
        Assertions.assertEquals(0, solution.maxProfit(prices));
    }

    @Test
    void testCase3() {
        int[] prices = {7, 2, 6, 1, 2};

        BestTimeToBuyAndSellStock solution = new BestTimeToBuyAndSellStock();
        // edge-case 1
        Assertions.assertEquals(4, solution.maxProfit(prices));
    }

    @Test
    void testCase4() {
        int[] prices = {7, 2, 6, 1, 8};

        BestTimeToBuyAndSellStock solution = new BestTimeToBuyAndSellStock();
        // edge-case 2
        Assertions.assertEquals(7, solution.maxProfit(prices));
    }
}
