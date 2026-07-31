package com.nexusuniverse.economy.stocks;

public class Stock {

    private final String key;
    private final String companyName;
    private double currentPrice;
    private double previousPrice;
    private final double volatility;
    private final double minPrice;
    private final double maxPrice;

    public Stock(String key, String companyName, double startingPrice, double volatility, double minPrice, double maxPrice) {
        this.key = key;
        this.companyName = companyName;
        this.currentPrice = startingPrice;
        this.previousPrice = startingPrice;
        this.volatility = volatility;
        this.minPrice = minPrice;
        this.maxPrice = maxPrice;
    }

    public String key() {
        return key;
    }

    public String companyName() {
        return companyName;
    }

    public double currentPrice() {
        return currentPrice;
    }

    public double volatility() {
        return volatility;
    }

    /** Applies a percentage change (e.g. 0.02 = +2%, -0.05 = -5%), clamped to this stock's own min/max bounds. */
    public void applyPriceChange(double percentChange) {
        previousPrice = currentPrice;
        currentPrice = Math.max(minPrice, Math.min(maxPrice, currentPrice * (1 + percentChange)));
    }

    /** Used when loading a persisted price -- doesn't move previousPrice, so trend arrows aren't wrong on the tick right after a restart. */
    public void setPriceDirect(double price) {
        currentPrice = Math.max(minPrice, Math.min(maxPrice, price));
        previousPrice = currentPrice;
    }

    public boolean isUp() {
        return currentPrice > previousPrice;
    }

    public boolean isDown() {
        return currentPrice < previousPrice;
    }
}
