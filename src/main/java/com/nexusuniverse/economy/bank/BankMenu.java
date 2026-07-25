package com.nexusuniverse.economy.bank;

import com.nexusuniverse.economy.AccountManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * The bank GUI: a live balance/interest display, a one-click "deposit
 * everything you're carrying" button, and a row of preset withdraw
 * amounts that hand you real physical bills on the spot. Custom
 * (non-preset) amounts still go through /bank withdraw <amount> --
 * the GUI is the fast path, the command is the precise one.
 *
 * Buttons are identified by a PDC tag holding an action string, not by
 * fixed slot numbers, so BankMenuListener reads intent directly off
 * the clicked item rather than assuming layout.
 */
public class BankMenu {

    private final Plugin plugin;
    private final AccountManager accounts;
    private final NamespacedKey actionKey;

    public BankMenu(Plugin plugin, AccountManager accounts) {
        this.plugin = plugin;
        this.accounts = accounts;
        this.actionKey = new NamespacedKey(plugin, "bank_action");
    }

    public NamespacedKey actionKey() {
        return actionKey;
    }

    public Inventory build(Player player) {
        BankMenuHolder holder = new BankMenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "Bank");
        holder.setInventory(inv);

        accounts.createAccount(player.getUniqueId());
        double balance = accounts.getBalance(player.getUniqueId());
        boolean interestEnabled = plugin.getConfig().getBoolean("bank.interest-enabled", true);
        double rate = plugin.getConfig().getDouble("bank.interest-rate", 0.01);
        double minBalance = plugin.getConfig().getDouble("bank.interest-minimum-balance", 1.0);

        inv.setItem(4, balanceItem(balance, interestEnabled, rate, minBalance));
        inv.setItem(11, depositButton());

        int[] presets = presetAmounts();
        int[] presetSlots = {13, 14, 15, 16, 17};
        for (int i = 0; i < presets.length && i < presetSlots.length; i++) {
            inv.setItem(presetSlots[i], withdrawButton(presets[i]));
        }

        inv.setItem(22, customAmountHint());
        return inv;
    }

    private int[] presetAmounts() {
        List<Integer> configured = plugin.getConfig().getIntegerList("bank.withdraw-presets");
        if (configured.isEmpty()) return new int[]{10, 50, 100, 500, 1000};
        return configured.stream().mapToInt(Integer::intValue).toArray();
    }

    private ItemStack balanceItem(double balance, boolean interestEnabled, double rate, double minBalance) {
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Balance: $" + String.format("%.2f", balance));

        List<String> lore = new ArrayList<>();
        if (interestEnabled) {
            lore.add(ChatColor.GRAY + "Earning " + ChatColor.GREEN + String.format("%.1f%%", rate * 100) + ChatColor.GRAY + " interest per cycle");
            if (minBalance > 0) {
                lore.add(ChatColor.DARK_GRAY + "(requires a balance of at least $" + String.format("%.2f", minBalance) + ")");
            }
        } else {
            lore.add(ChatColor.DARK_GRAY + "Interest is currently disabled");
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack depositButton() {
        ItemStack item = new ItemStack(Material.CHEST);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "Deposit Held Cash");
        meta.setLore(List.of(ChatColor.GRAY + "Click to deposit every bill", ChatColor.GRAY + "you're carrying."));
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "deposit");
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack withdrawButton(int amount) {
        ItemStack item = new ItemStack(Material.SUNFLOWER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.YELLOW + "" + ChatColor.BOLD + "Withdraw $" + amount);
        meta.setLore(List.of(ChatColor.GRAY + "Click for $" + amount + " in physical bills."));
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "withdraw_" + amount);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack customAmountHint() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "Need a different amount?");
        meta.setLore(List.of(ChatColor.GRAY + "Use /bank withdraw <amount>", ChatColor.GRAY + "for any exact whole-dollar amount."));
        item.setItemMeta(meta);
        return item;
    }
}
