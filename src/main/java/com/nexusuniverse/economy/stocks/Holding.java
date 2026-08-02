package com.nexusuniverse.economy.stocks;

public class Holding {

    private int shares;
    private double totalCostBasis;

    public Holding() {}

    public Holding(int shares, double totalCostBasis) {
        this.shares = shares;
        this.totalCostBasis = totalCostBasis;
    }

    public int shares() {
        return shares;
    }

    public double totalCostBasis() {
        return totalCostBasis;
    }

    public double averageCost() {
        return shares > 0 ? totalCostBasis / shares : 0;
    }

    public void addShares(int amount, double costPerShare) {
        shares += amount;
        totalCostBasis += amount * costPerShare;
    }

    /** Removes shares, reducing cost basis proportionally, and returns the realized profit/loss on this specific sale. */
    public double removeShares(int amount, double sellPricePerShare) {
        int actual = Math.min(amount, shares);
        double avgCost = averageCost();
        double costRemoved = avgCost * actual;

        totalCostBasis -= costRemoved;
        shares -= actual;

        double proceeds = actual * sellPricePerShare;
        return proceeds - costRemoved;
    }
}
