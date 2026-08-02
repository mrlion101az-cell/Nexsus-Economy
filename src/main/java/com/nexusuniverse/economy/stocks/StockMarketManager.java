package com.nexusuniverse.economy.stocks;

import com.nexusuniverse.economy.AccountManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The roster lives in config.yml, not hardcoded Java -- adding a new
 * stock is a new entry there. Prices move on their own every tick via a
 * random walk bounded by each stock's own volatility, plus a rare
 * chance of a market-wide crash or boom that hits every stock at once.
 * Buying and selling go straight through the same AccountManager the
 * rest of NexusEconomy uses -- no separate currency, no Vault bridge
 * needed since this lives inside the plugin that owns the bank.
 */
public class StockMarketManager {

    private final Plugin plugin;
    private final AccountManager accounts;
    private final File dataFile;
    private final Map<String, Stock> roster = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Holding>> portfolios = new HashMap<>();
    private final Random random = new Random();

    public StockMarketManager(Plugin plugin, AccountManager accounts) {
        this.plugin = plugin;
        this.accounts = accounts;
        this.dataFile = new File(plugin.getDataFolder(), "stock-market.yml");
        loadRoster();
        loadState();
    }

    private void loadRoster() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("stocks.roster");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            try {
                String companyName = section.getString(key + ".company-name", key);
                double startingPrice = section.getDouble(key + ".starting-price", 50.0);
                double volatility = section.getDouble(key + ".volatility", plugin.getConfig().getDouble("stocks.default-volatility", 0.03));
                double minPrice = section.getDouble(key + ".min-price", 1.0);
                double maxPrice = section.getDouble(key + ".max-price", 10_000.0);
                roster.put(key, new Stock(key, companyName, startingPrice, volatility, minPrice, maxPrice));
            } catch (Exception e) {
                plugin.getLogger().warning("NexusEconomy: skipping malformed stock entry '" + key + "'");
            }
        }
    }

    public List<Stock> roster() {
        return new ArrayList<>(roster.values());
    }

    public Stock getStock(String key) {
        return roster.get(key.toLowerCase());
    }

    /** Called on the configured interval: a random walk per stock, plus a rare chance of a market-wide event. */
    public void tick() {
        if (!plugin.getConfig().getBoolean("stocks.enabled", true)) return;

        double eventChance = plugin.getConfig().getDouble("stocks.event-chance", 0.05);
        if (random.nextDouble() < eventChance) {
            triggerMarketEvent();
        } else {
            for (Stock stock : roster.values()) {
                double change = (random.nextDouble() * 2 - 1) * stock.volatility(); // +/- volatility
                stock.applyPriceChange(change);
            }
        }
        saveState();
    }

    private void triggerMarketEvent() {
        boolean crash = random.nextBoolean();
        double minPercent = plugin.getConfig().getDouble(crash ? "stocks.crash-min-percent" : "stocks.boom-min-percent", 10.0);
        double maxPercent = plugin.getConfig().getDouble(crash ? "stocks.crash-max-percent" : "stocks.boom-max-percent", 30.0);
        double magnitude = (minPercent + random.nextDouble() * (maxPercent - minPercent)) / 100.0;

        for (Stock stock : roster.values()) {
            stock.applyPriceChange(crash ? -magnitude : magnitude);
        }

        String message = crash
                ? "§4§lMARKET CRASH: §cEvery stock just took a hit."
                : "§a§lMARKET BOOM: §aEvery stock just surged.";
        Bukkit.broadcastMessage(message);
    }

    public String buy(Player player, String stockKey, int shares) {
        Stock stock = getStock(stockKey);
        if (stock == null) return "No stock by that symbol.";
        if (shares <= 0) return "Share amount must be positive.";

        double cost = stock.currentPrice() * shares;
        if (!accounts.withdraw(player.getUniqueId(), cost)) return "You can't afford that (total: $" + String.format("%.2f", cost) + ").";

        holdingsFor(player.getUniqueId()).computeIfAbsent(stock.key(), k -> new Holding()).addShares(shares, stock.currentPrice());
        saveState();
        return null; // success
    }

    public String sell(Player player, String stockKey, int shares) {
        Stock stock = getStock(stockKey);
        if (stock == null) return "No stock by that symbol.";
        if (shares <= 0) return "Share amount must be positive.";

        Map<String, Holding> portfolio = holdingsFor(player.getUniqueId());
        Holding holding = portfolio.get(stock.key());
        if (holding == null || holding.shares() < shares) return "You don't own that many shares.";

        double profitLoss = holding.removeShares(shares, stock.currentPrice());
        accounts.deposit(player.getUniqueId(), shares * stock.currentPrice());

        String sign = profitLoss >= 0 ? "§a+" : "§c";
        player.sendMessage("§7Realized " + sign + "$" + String.format("%.2f", Math.abs(profitLoss)) + "§7 on that sale.");
        saveState();
        return null; // success
    }

    public Map<String, Holding> holdingsFor(UUID playerId) {
        return portfolios.computeIfAbsent(playerId, id -> new HashMap<>());
    }

    private void loadState() {
        if (!dataFile.exists()) return;
        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);

        ConfigurationSection prices = data.getConfigurationSection("prices");
        if (prices != null) {
            for (String key : prices.getKeys(false)) {
                Stock stock = roster.get(key);
                if (stock != null) stock.setPriceDirect(prices.getDouble(key));
            }
        }

        ConfigurationSection portfoliosSection = data.getConfigurationSection("portfolios");
        if (portfoliosSection != null) {
            for (String uuidKey : portfoliosSection.getKeys(false)) {
                try {
                    UUID playerId = UUID.fromString(uuidKey);
                    ConfigurationSection playerSection = portfoliosSection.getConfigurationSection(uuidKey);
                    if (playerSection == null) continue;

                    Map<String, Holding> holdings = holdingsFor(playerId);
                    for (String stockKey : playerSection.getKeys(false)) {
                        int shares = playerSection.getInt(stockKey + ".shares", 0);
                        double costBasis = playerSection.getDouble(stockKey + ".cost-basis", 0.0);
                        if (shares > 0) holdings.put(stockKey, new Holding(shares, costBasis));
                    }
                } catch (IllegalArgumentException ignored) {
                    // skip a malformed entry
                }
            }
        }
    }

    private void saveState() {
        YamlConfiguration data = new YamlConfiguration();
        for (Stock stock : roster.values()) {
            data.set("prices." + stock.key(), stock.currentPrice());
        }
        for (Map.Entry<UUID, Map<String, Holding>> entry : portfolios.entrySet()) {
            String uuidKey = entry.getKey().toString();
            for (Map.Entry<String, Holding> holdingEntry : entry.getValue().entrySet()) {
                Holding holding = holdingEntry.getValue();
                if (holding.shares() <= 0) continue;
                data.set("portfolios." + uuidKey + "." + holdingEntry.getKey() + ".shares", holding.shares());
                data.set("portfolios." + uuidKey + "." + holdingEntry.getKey() + ".cost-basis", holding.totalCostBasis());
            }
        }
        try {
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "NexusEconomy: failed to save stock-market.yml", e);
        }
    }
}
