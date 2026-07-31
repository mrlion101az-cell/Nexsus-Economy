package com.nexusuniverse.economy.auction;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public class AuctionCommand implements CommandExecutor {

    private final AuctionManager auctions;

    public AuctionCommand(AuctionManager auctions) {
        this.auctions = auctions;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            sendList(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "sell" -> handleSell(player, args);
            case "bid" -> handleBid(player, args);
            case "claim" -> handleClaim(player);
            default -> player.sendMessage("§cUsage: /auction <list|sell <price> <minutes>|bid <id> <amount>|claim>");
        }
        return true;
    }

    private void handleSell(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage("§cUsage: /auction sell <starting-price> <duration-minutes> (hold the item to list)");
            return;
        }
        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (inHand.getType().isAir()) {
            player.sendMessage("§cYou need to be holding the item you want to auction.");
            return;
        }
        double startPrice;
        int minutes;
        try {
            startPrice = Double.parseDouble(args[1]);
            minutes = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            player.sendMessage("§cPrice and duration must be numbers.");
            return;
        }
        if (startPrice <= 0 || minutes <= 0) {
            player.sendMessage("§cPrice and duration must be positive.");
            return;
        }

        ItemStack listed = inHand.clone();
        player.getInventory().setItemInMainHand(null);
        AuctionListing listing = auctions.createPlayerAuction(player, listed, startPrice, minutes);
        player.sendMessage("§aListed " + listed.getType() + " starting at $" + startPrice + " for " + minutes + " minutes. §7ID: " + listing.id());
    }

    private void handleBid(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage("§cUsage: /auction bid <id> <amount>");
            return;
        }
        AuctionListing listing = auctions.findByIdOrPrefix(args[1]);
        if (listing == null) {
            player.sendMessage("§cAuction not found.");
            return;
        }
        double amount;
        try {
            amount = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            player.sendMessage("§cBid amount must be a number.");
            return;
        }
        if (auctions.bid(player, listing.id(), amount)) {
            player.sendMessage("§aBid placed: $" + amount + " on " + listing.item().getType() + ".");
        } else {
            player.sendMessage("§cBid failed -- too low, insufficient funds, expired, or it's your own listing.");
        }
    }

    private void handleClaim(Player player) {
        List<ItemStack> owed = auctions.takeClaims(player.getUniqueId());
        if (owed.isEmpty()) {
            player.sendMessage("§7Nothing to claim.");
            return;
        }
        for (ItemStack item : owed) {
            var leftover = player.getInventory().addItem(item);
            leftover.values().forEach(extra -> player.getWorld().dropItemNaturally(player.getLocation(), extra));
        }
        player.sendMessage("§aClaimed " + owed.size() + " item(s).");
    }

    private void sendList(Player player) {
        List<AuctionListing> active = auctions.activeListings();
        if (active.isEmpty()) {
            player.sendMessage("§7No active auctions.");
            return;
        }
        player.sendMessage("§7--- Active Auctions ---");
        for (AuctionListing listing : active) {
            long secondsLeft = Math.max(0, (listing.endTimeMillis() - System.currentTimeMillis()) / 1000);
            player.sendMessage("§f" + listing.item().getType() + " §7[" + listing.sellerName() + "] - "
                    + "§a$" + String.format("%.2f", listing.currentBid()) + " §7by "
                    + (listing.hasBid() ? listing.currentBidderName() : "nobody yet")
                    + " §8[" + listing.id().toString().substring(0, 8) + "] §7(" + (secondsLeft / 60) + "m left)");
        }
        player.sendMessage("§7/auction bid <id> <amount> to bid (the short ID above works).");
    }
}
