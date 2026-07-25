package com.nexusuniverse.economy.auction;

import com.nexusuniverse.economy.AccountManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Auctions -- player-listed and server-generated -- share one pool, one
 * GUI/command surface, and one settlement path. The only real
 * difference: a server auction has no seller to pay out (the winning
 * bid just isn't credited to anyone), and its item comes from a
 * configurable pool instead of a player's own inventory.
 *
 * Bidding escrows the bidder's money immediately and refunds the
 * previous bidder the instant they're outbid, so nobody ever has more
 * than one active bid's worth of money tied up. Settlement (paying the
 * seller, handing over the item) doesn't require the seller or winner
 * to be online -- results go into a claims inbox instead.
 */
public class AuctionManager {

    private final Plugin plugin;
    private final AccountManager accounts;
    private final File auctionsFile;
    private final File claimsFile;
    private final Map<UUID, AuctionListing> listings = new HashMap<>();
    private final Map<UUID, List<ItemStack>> claims = new HashMap<>();

    public AuctionManager(Plugin plugin, AccountManager accounts) {
        this.plugin = plugin;
        this.accounts = accounts;
        this.auctionsFile = new File(plugin.getDataFolder(), "auctions.yml");
        this.claimsFile = new File(plugin.getDataFolder(), "claims.yml");
        load();
    }

    public AuctionListing createPlayerAuction(Player seller, ItemStack item, double startPrice, int durationMinutes) {
        long endTime = System.currentTimeMillis() + (durationMinutes * 60_000L);
        AuctionListing listing = new AuctionListing(UUID.randomUUID(), seller.getUniqueId(), seller.getName(), item, startPrice, endTime);
        listings.put(listing.id(), listing);
        save();
        return listing;
    }

    public AuctionListing createServerAuction(ItemStack item, double startPrice, int durationMinutes) {
        long endTime = System.currentTimeMillis() + (durationMinutes * 60_000L);
        AuctionListing listing = new AuctionListing(UUID.randomUUID(), null, "Server", item, startPrice, endTime);
        listings.put(listing.id(), listing);
        save();
        return listing;
    }

    public boolean bid(Player bidder, UUID listingId, double amount) {
        AuctionListing listing = listings.get(listingId);
        if (listing == null || listing.isExpired()) return false;
        if (listing.sellerId() != null && listing.sellerId().equals(bidder.getUniqueId())) return false;

        double minimumBid = listing.hasBid() ? listing.currentBid() + 0.01 : listing.startPrice();
        if (amount < minimumBid) return false;
        if (!accounts.withdraw(bidder.getUniqueId(), amount)) return false;

        if (listing.hasBid()) {
            accounts.deposit(listing.currentBidderId(), listing.currentBid()); // refund whoever we just outbid
        }
        listing.placeBid(bidder.getUniqueId(), bidder.getName(), amount);
        save();
        return true;
    }

    public AuctionListing findByIdOrPrefix(String idOrPrefix) {
        try {
            AuctionListing exact = listings.get(UUID.fromString(idOrPrefix));
            if (exact != null) return exact;
        } catch (IllegalArgumentException ignored) {
            // fall through to prefix matching
        }
        for (AuctionListing listing : listings.values()) {
            if (listing.id().toString().startsWith(idOrPrefix)) return listing;
        }
        return null;
    }

    public List<AuctionListing> activeListings() {
        List<AuctionListing> active = new ArrayList<>();
        for (AuctionListing listing : listings.values()) {
            if (!listing.isExpired()) active.add(listing);
        }
        return active;
    }

    /** Called periodically: pays out/delivers any auction whose time is up, then forgets it. */
    public void settleExpired() {
        boolean changed = false;
        for (AuctionListing listing : listings.values()) {
            if (!listing.isExpired() || listing.isSettled()) continue;
            settle(listing);
            changed = true;
        }
        if (changed) {
            listings.values().removeIf(AuctionListing::isSettled);
            save();
        }
    }

    private void settle(AuctionListing listing) {
        if (listing.hasBid()) {
            queueClaim(listing.currentBidderId(), listing.item());
            if (!listing.isServerAuction()) {
                accounts.deposit(listing.sellerId(), listing.currentBid());
            }
            Player winner = Bukkit.getPlayer(listing.currentBidderId());
            if (winner != null) {
                winner.sendMessage("§aYou won an auction for " + listing.item().getType() + "! Use /auction claim to collect it.");
            }
        } else if (!listing.isServerAuction()) {
            queueClaim(listing.sellerId(), listing.item()); // no bids -- return it to the seller
        }
        listing.markSettled();
    }

    private void queueClaim(UUID playerId, ItemStack item) {
        claims.computeIfAbsent(playerId, id -> new ArrayList<>()).add(item.clone());
    }

    public List<ItemStack> takeClaims(UUID playerId) {
        List<ItemStack> owed = claims.remove(playerId);
        if (owed != null) save();
        return owed != null ? owed : List.of();
    }

    private void load() {
        if (auctionsFile.exists()) {
            YamlConfiguration data = YamlConfiguration.loadConfiguration(auctionsFile);
            ConfigurationSection root = data.getConfigurationSection("listings");
            if (root != null) {
                for (String key : root.getKeys(false)) {
                    try {
                        UUID id = UUID.fromString(key);
                        ConfigurationSection section = root.getConfigurationSection(key);
                        if (section == null) continue;

                        UUID sellerId = section.contains("sellerId") ? UUID.fromString(section.getString("sellerId")) : null;
                        String sellerName = section.getString("sellerName", "Server");
                        ItemStack item = section.getItemStack("item");
                        double startPrice = section.getDouble("startPrice");
                        long endTime = section.getLong("endTime");
                        if (item == null) continue;

                        AuctionListing listing = new AuctionListing(id, sellerId, sellerName, item, startPrice, endTime);
                        if (section.contains("currentBidderId")) {
                            listing.placeBid(UUID.fromString(section.getString("currentBidderId")),
                                    section.getString("currentBidderName"), section.getDouble("currentBid"));
                        }
                        listings.put(id, listing);
                    } catch (Exception ignored) {
                        // skip a malformed entry rather than fail the whole load
                    }
                }
            }
        }

        if (claimsFile.exists()) {
            YamlConfiguration data = YamlConfiguration.loadConfiguration(claimsFile);
            for (String key : data.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    List<?> raw = data.getList(key);
                    if (raw == null) continue;
                    List<ItemStack> items = new ArrayList<>();
                    for (Object obj : raw) {
                        if (obj instanceof ItemStack stack) items.add(stack);
                    }
                    if (!items.isEmpty()) claims.put(id, items);
                } catch (Exception ignored) {
                    // skip a malformed entry
                }
            }
        }
    }

    public void save() {
        YamlConfiguration auctionData = new YamlConfiguration();
        for (AuctionListing listing : listings.values()) {
            String key = "listings." + listing.id();
            if (listing.sellerId() != null) auctionData.set(key + ".sellerId", listing.sellerId().toString());
            auctionData.set(key + ".sellerName", listing.sellerName());
            auctionData.set(key + ".item", listing.item());
            auctionData.set(key + ".startPrice", listing.startPrice());
            auctionData.set(key + ".endTime", listing.endTimeMillis());
            if (listing.hasBid()) {
                auctionData.set(key + ".currentBid", listing.currentBid());
                auctionData.set(key + ".currentBidderId", listing.currentBidderId().toString());
                auctionData.set(key + ".currentBidderName", listing.currentBidderName());
            }
        }
        try {
            auctionData.save(auctionsFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "NexusEconomy: failed to save auctions.yml", e);
        }

        YamlConfiguration claimsData = new YamlConfiguration();
        for (Map.Entry<UUID, List<ItemStack>> entry : claims.entrySet()) {
            claimsData.set(entry.getKey().toString(), entry.getValue());
        }
        try {
            claimsData.save(claimsFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "NexusEconomy: failed to save claims.yml", e);
        }
    }
}
