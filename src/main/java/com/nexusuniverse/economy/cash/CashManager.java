package com.nexusuniverse.economy.cash;

import com.nexusuniverse.economy.AccountManager;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts between a bank balance and physical cash items -- withdraw
 * breaks an amount down into real denominations (greedy, largest first),
 * deposit sweeps the player's whole inventory for cash items and banks
 * their total value in one go.
 */
public class CashManager {

    public enum Result { SUCCESS, INSUFFICIENT_FUNDS, NOT_WHOLE_DOLLARS, INVENTORY_FULL }

    private final AccountManager accounts;
    private final CashItems cashItems;
    private final int[] denominations; // expected sorted descending, e.g. {100, 50, 20, 10, 5, 1}

    public CashManager(AccountManager accounts, CashItems cashItems, int[] denominations) {
        this.accounts = accounts;
        this.cashItems = cashItems;
        this.denominations = denominations;
    }

    public Result withdraw(Player player, double amount) {
        if (amount <= 0 || amount != Math.floor(amount)) {
            return Result.NOT_WHOLE_DOLLARS; // cash denominations are all whole dollars -- can't represent cents physically
        }
        int wholeAmount = (int) amount;

        if (!accounts.has(player.getUniqueId(), wholeAmount)) {
            return Result.INSUFFICIENT_FUNDS;
        }

        List<ItemStack> bills = new ArrayList<>();
        int remaining = wholeAmount;
        for (int denomination : denominations) {
            while (remaining >= denomination) {
                bills.add(cashItems.create(denomination));
                remaining -= denomination;
            }
        }

        int freeSlots = 0;
        for (ItemStack slot : player.getInventory().getStorageContents()) {
            if (slot == null || slot.getType().isAir()) freeSlots++;
        }
        if (freeSlots < bills.size()) {
            return Result.INVENTORY_FULL;
        }

        accounts.withdraw(player.getUniqueId(), wholeAmount);
        for (ItemStack bill : bills) {
            player.getInventory().addItem(bill);
        }
        return Result.SUCCESS;
    }

    /** Sweeps the player's whole inventory for cash items, removes them, and banks the total. Returns the amount deposited. */
    public int depositAll(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getStorageContents();
        int total = 0;

        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null) continue;
            Integer value = cashItems.readValue(stack);
            if (value == null) continue;

            total += value * stack.getAmount();
            contents[i] = null;
        }
        inv.setStorageContents(contents);

        if (total > 0) {
            accounts.createAccount(player.getUniqueId());
            accounts.deposit(player.getUniqueId(), total);
        }
        return total;
    }
}
