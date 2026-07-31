package com.nexusuniverse.economy;

import com.nexusuniverse.economy.auction.AuctionCommand;
import com.nexusuniverse.economy.auction.AuctionManager;
import com.nexusuniverse.economy.bank.BankMenu;
import com.nexusuniverse.economy.bank.BankMenuListener;
import com.nexusuniverse.economy.cash.CashItems;
import com.nexusuniverse.economy.cash.CashManager;
import com.nexusuniverse.economy.credit.CreditCommand;
import com.nexusuniverse.economy.credit.CreditManager;
import com.nexusuniverse.economy.credit.SeasonPoller;
import com.nexusuniverse.economy.orders.OrderBoardManager;
import com.nexusuniverse.economy.orders.OrderCommand;
import com.nexusuniverse.economy.shop.ShopCommand;
import com.nexusuniverse.economy.shop.ShopListener;
import com.nexusuniverse.economy.shop.ShopManager;
import com.nexusuniverse.economy.stocks.StockMarketCommand;
import com.nexusuniverse.economy.stocks.StockMarketManager;
import com.nexusuniverse.economy.vault.NexusVaultEconomy;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.Random;

public class NexusEconomyPlugin extends JavaPlugin {

    private AccountManager accounts;
    private CashManager cashManager;
    private ShopManager shopManager;
    private StockMarketManager stockMarket;
    private OrderBoardManager orderBoard;
    private AuctionManager auctionManager;
    private CreditManager creditManager;
    private final SeasonPoller seasonPoller = new SeasonPoller();
    private long lastBillingMillis;
    private final Random random = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.accounts = new AccountManager(this, getConfig().getDouble("economy.starting-balance", 0.0));

        CashItems cashItems = new CashItems(this);
        int[] denominations = getConfig().getIntegerList("cash.denominations").stream()
                .mapToInt(Integer::intValue)
                .toArray();
        if (denominations.length == 0) {
            denominations = new int[]{100, 50, 20, 10, 5, 1};
        }
        this.cashManager = new CashManager(accounts, cashItems, denominations);

        this.shopManager = new ShopManager(this, accounts);
        this.stockMarket = new StockMarketManager(this, accounts);
        getServer().getPluginManager().registerEvents(new ShopListener(shopManager), this);

        BankMenu bankMenu = new BankMenu(this, accounts);
        getServer().getPluginManager().registerEvents(new BankMenuListener(bankMenu, cashManager), this);

        this.orderBoard = new OrderBoardManager(this, accounts);
        this.auctionManager = new AuctionManager(this, accounts);
        this.creditManager = new CreditManager(this, accounts);

        getCommand("bank").setExecutor(new BankCommand(accounts, cashManager, bankMenu));
        getCommand("shop").setExecutor(new ShopCommand(shopManager));
        getCommand("stocks").setExecutor(new StockMarketCommand(stockMarket));
        getCommand("economyadmin").setExecutor(new EconomyAdminCommand(accounts));
        getCommand("orders").setExecutor(new OrderCommand(orderBoard));
        getCommand("auction").setExecutor(new AuctionCommand(auctionManager));
        getCommand("credit").setExecutor(new CreditCommand(creditManager));

        registerWithVault();

        // auction expiry sweep -- settles anything whose timer ran out, pays sellers, queues winner claims
        Bukkit.getScheduler().runTaskTimer(this, auctionManager::settleExpired, 20L * 30, 20L * 30);

        // stock market: price movement on its own configured interval
        long stockTickTicks = 20L * 60 * getConfig().getInt("stocks.tick-interval-minutes", 5);
        Bukkit.getScheduler().runTaskTimer(this, stockMarket::tick, stockTickTicks, stockTickTicks);

        if (getConfig().getBoolean("auction.auto-enabled", true)) {
            long intervalTicks = 20L * 60 * getConfig().getInt("auction.auto-interval-minutes", 30);
            Bukkit.getScheduler().runTaskTimer(this, this::spawnAutoAuction, intervalTicks, intervalTicks);
        }

        // credit billing + savings interest: checked every minute -- fires the instant NexusSeasons'
        // day counter rolls back to 1 (a new month) if it's installed, otherwise falls back to a
        // configurable real-time interval
        this.lastBillingMillis = System.currentTimeMillis();
        Bukkit.getScheduler().runTaskTimer(this, this::checkBilling, 20L * 60, 20L * 60);

        getLogger().info("NexusEconomy enabled -- Bank, physical cash, shop, order board, auction house, credit, and the stock market are live.");
    }

    private void checkBilling() {
        if (seasonPoller.checkForNewMonth()) {
            runBillingAndInterest();
            lastBillingMillis = System.currentTimeMillis();
            return;
        }
        if (!seasonPoller.isConnected()) {
            long fallbackMillis = getConfig().getLong("credit.fallback-billing-interval-hours", 168) * 3_600_000L;
            if (System.currentTimeMillis() - lastBillingMillis >= fallbackMillis) {
                runBillingAndInterest();
                lastBillingMillis = System.currentTimeMillis();
            }
        }
    }

    /** One cycle, two things: credit statements go out, and savings interest posts to every eligible balance. */
    private void runBillingAndInterest() {
        creditManager.runBillingCycle();
        if (getConfig().getBoolean("bank.interest-enabled", true)) {
            double rate = getConfig().getDouble("bank.interest-rate", 0.01);
            double minBalance = getConfig().getDouble("bank.interest-minimum-balance", 1.0);
            accounts.applyInterest(rate, minBalance);
        }
    }

    /** Picks a random entry from auction.pool in config.yml and lists it as a server auction -- fully admin-customizable. */
    private void spawnAutoAuction() {
        List<?> pool = getConfig().getList("auction.pool");
        if (pool == null || pool.isEmpty()) return;

        Object raw = pool.get(random.nextInt(pool.size()));
        if (!(raw instanceof Map<?, ?> entry)) return;

        try {
            Material material = Material.valueOf(String.valueOf(entry.get("material")));
            int amount = entry.get("amount") instanceof Number n ? n.intValue() : 1;
            double startPrice = entry.get("start-price") instanceof Number n ? n.doubleValue() : 10.0;
            int duration = getConfig().getInt("auction.duration-minutes", 60);

            auctionManager.createServerAuction(new ItemStack(material, amount), startPrice, duration);
            Bukkit.broadcastMessage("§6§lAuction House: §fA new server auction just went up -- " + amount + "x "
                    + material.name() + "! §7/auction list");
        } catch (Exception e) {
            getLogger().warning("NexusEconomy: bad entry in auction.pool config, skipping this round");
        }
    }

    private void registerWithVault() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().warning("NexusEconomy: Vault not found -- other plugins that expect a Vault economy won't see this Bank. "
                    + "The Bank, cash, and shop all still work fine on their own.");
            return;
        }

        Economy economy = new NexusVaultEconomy(
                accounts,
                getConfig().getString("economy.currency-singular", "Dollar"),
                getConfig().getString("economy.currency-plural", "Dollars"),
                getConfig().getInt("economy.fractional-digits", 2)
        );
        getServer().getServicesManager().register(Economy.class, economy, this, ServicePriority.Highest);
        getLogger().info("NexusEconomy: registered as the Vault economy provider.");
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
    }

    public AccountManager getAccounts() {
        return accounts;
    }

    public ShopManager getShopManager() {
        return shopManager;
    }

    public StockMarketManager getStockMarket() {
        return stockMarket;
    }

    public OrderBoardManager getOrderBoard() {
        return orderBoard;
    }

    public AuctionManager getAuctionManager() {
        return auctionManager;
    }

    public CreditManager getCreditManager() {
        return creditManager;
    }
}
