package com.nexusuniverse.economy.orders;

import com.nexusuniverse.economy.AccountManager;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * A bounty board: a player posts an order for N of a material at a
 * price-per-item and escrows the FULL cost up front -- this is what
 * makes it safe for anyone to fulfill without the two players needing
 * to coordinate, trust each other, or even be online at the same time.
 * The poster can never end up owing more than they had; a fulfiller is
 * paid the instant they turn items in, straight out of that escrow.
 */
public class OrderBoardManager {

    private final Plugin plugin;
    private final AccountManager accounts;
    private final File dataFile;
    private final List<Order> orders = new ArrayList<>();

    public OrderBoardManager(Plugin plugin, AccountManager accounts) {
        this.plugin = plugin;
        this.accounts = accounts;
        this.dataFile = new File(plugin.getDataFolder(), "orders.yml");
        load();
    }

    public Order createOrder(Player poster, Material material, int quantity, double payPerItem) {
        double totalCost = round2(quantity * payPerItem);
        if (!accounts.withdraw(poster.getUniqueId(), totalCost)) return null;

        Order order = new Order(UUID.randomUUID(), poster.getUniqueId(), poster.getName(), material, quantity, payPerItem);
        orders.add(order);
        save();
        return order;
    }

    public boolean cancelOrder(String idOrPrefix, UUID requesterId) {
        Order order = findByIdOrPrefix(idOrPrefix);
        if (order == null || !order.posterId().equals(requesterId)) return false;

        double refund = round2(order.remainingQuantity() * order.payPerItem());
        accounts.deposit(requesterId, refund);
        orders.remove(order);
        save();
        return true;
    }

    /** Turns in as many matching held items as the fulfiller has (up to what's still owed), pays out immediately. */
    public int fulfill(Player fulfiller, String idOrPrefix) {
        Order order = findByIdOrPrefix(idOrPrefix);
        if (order == null) return 0;

        int held = countInInventory(fulfiller, order.material());
        int toFulfill = Math.min(held, order.remainingQuantity());
        if (toFulfill <= 0) return 0;

        removeFromInventory(fulfiller, order.material(), toFulfill);
        double payout = round2(toFulfill * order.payPerItem());
        accounts.deposit(fulfiller.getUniqueId(), payout);

        order.reduce(toFulfill);
        if (order.remainingQuantity() <= 0) {
            orders.remove(order);
        }
        save();
        return toFulfill;
    }

    public Order findByIdOrPrefix(String idOrPrefix) {
        try {
            UUID exact = UUID.fromString(idOrPrefix);
            for (Order order : orders) {
                if (order.id().equals(exact)) return order;
            }
            return null;
        } catch (IllegalArgumentException ignored) {
            // not a full UUID -- fall through to prefix matching so the shortened ID shown in /orders list still works
        }
        for (Order order : orders) {
            if (order.id().toString().startsWith(idOrPrefix)) return order;
        }
        return null;
    }

    public List<Order> activeOrders() {
        return new ArrayList<>(orders);
    }

    private int countInInventory(Player player, Material material) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() == material) count += stack.getAmount();
        }
        return count;
    }

    private void removeFromInventory(Player player, Material material, int amount) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        int remaining = amount;
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != material) continue;
            int take = Math.min(remaining, stack.getAmount());
            stack.setAmount(stack.getAmount() - take);
            if (stack.getAmount() <= 0) contents[i] = null;
            remaining -= take;
        }
        player.getInventory().setStorageContents(contents);
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        List<?> list = data.getList("orders");
        if (list == null) return;

        for (Object obj : list) {
            if (!(obj instanceof Map<?, ?> map)) continue;
            try {
                UUID id = UUID.fromString(String.valueOf(map.get("id")));
                UUID posterId = UUID.fromString(String.valueOf(map.get("posterId")));
                String posterName = String.valueOf(map.get("posterName"));
                Material material = Material.valueOf(String.valueOf(map.get("material")));
                int remaining = Integer.parseInt(String.valueOf(map.get("remaining")));
                double pay = Double.parseDouble(String.valueOf(map.get("pay")));
                orders.add(new Order(id, posterId, posterName, material, remaining, pay));
            } catch (Exception ignored) {
                // skip a malformed entry rather than fail the whole load
            }
        }
    }

    public void save() {
        YamlConfiguration data = new YamlConfiguration();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Order order : orders) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", order.id().toString());
            map.put("posterId", order.posterId().toString());
            map.put("posterName", order.posterName());
            map.put("material", order.material().name());
            map.put("remaining", order.remainingQuantity());
            map.put("pay", order.payPerItem());
            list.add(map);
        }
        data.set("orders", list);
        try {
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "NexusEconomy: failed to save orders.yml", e);
        }
    }
}
