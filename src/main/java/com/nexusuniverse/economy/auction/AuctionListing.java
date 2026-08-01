package com.nexusuniverse.economy.auction;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public class AuctionListing {

    private final UUID id;
    private final UUID sellerId; // null = server auction, no payout target
    private final String sellerName;
    private final ItemStack item;
    private final double startPrice;
    private double currentBid;
    private UUID currentBidderId;
    private String currentBidderName;
    private final long endTimeMillis;
    private boolean settled;

    public AuctionListing(UUID id, UUID sellerId, String sellerName, ItemStack item, double startPrice, long endTimeMillis) {
        this.id = id;
        this.sellerId = sellerId;
        this.sellerName = sellerName;
        this.item = item;
        this.startPrice = startPrice;
        this.currentBid = startPrice;
        this.endTimeMillis = endTimeMillis;
    }

    public UUID id() {
        return id;
    }

    public UUID sellerId() {
        return sellerId;
    }

    public String sellerName() {
        return sellerName;
    }

    public boolean isServerAuction() {
        return sellerId == null;
    }

    public ItemStack item() {
        return item;
    }

    public double startPrice() {
        return startPrice;
    }

    public double currentBid() {
        return currentBid;
    }

    public UUID currentBidderId() {
        return currentBidderId;
    }

    public String currentBidderName() {
        return currentBidderName;
    }

    public long endTimeMillis() {
        return endTimeMillis;
    }

    public boolean hasBid() {
        return currentBidderId != null;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() >= endTimeMillis;
    }

    public boolean isSettled() {
        return settled;
    }

    public void markSettled() {
        settled = true;
    }

    public void placeBid(UUID bidderId, String bidderName, double amount) {
        this.currentBid = amount;
        this.currentBidderId = bidderId;
        this.currentBidderName = bidderName;
    }
}
