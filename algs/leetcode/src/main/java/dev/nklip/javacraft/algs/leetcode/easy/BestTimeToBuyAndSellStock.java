package dev.nklip.javacraft.algs.leetcode.easy;

/**
 * 121. Best Time to Buy and Sell Stock
 * <p>
 * You are given an array prices where prices[i] is the price of a given stock on the ith day.
 * <p>
 * You want to maximize your profit by choosing a single day to buy one stock and choosing a different day in the future to sell that stock.
 * <p>
 * Return the maximum profit you can achieve from this transaction. If you cannot achieve any profit, return 0.
 */
public class BestTimeToBuyAndSellStock {

    public int maxProfit(int[] prices) {
        int[] profits = new int[prices.length - 1];

        int smallest = -1;
        int maxSoFar = 0;

        // 7, 2, 6, 1, 2
        // 7, 2, 6, 1, 8
        for (int price : prices) {
            if (smallest == -1) {
                smallest = price;
            }
            if (smallest > price) {
                smallest = price;
            } else if (smallest < price) {
                profits[maxSoFar++] = price - smallest;
            }
        }

        int maxProfit = 0;
        for (int profit : profits) {
            if (maxProfit < profit) {
                maxProfit = profit;
            }
        }
        return maxProfit;
    }
}
