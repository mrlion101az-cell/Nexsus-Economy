package com.nexusuniverse.economy.bank;

import com.nexusuniverse.economy.cash.CashManager;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

public class BankMenuListener implements Listener {

    private final BankMenu bankMenu;
    private final CashManager cashManager;

    public BankMenuListener(BankMenu bankMenu, CashManager cashManager) {
        this.bankMenu = bankMenu;
        this.cashManager = cashManager;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        if (!(topInventory.getHolder() instanceof BankMenuHolder)) return;

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || event.getClickedInventory() != topInventory) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir() || !clicked.hasItemMeta()) return;

        String action = clicked.getItemMeta().getPersistentDataContainer().get(bankMenu.actionKey(), PersistentDataType.STRING);
        if (action == null) return;

        if (action.equals("deposit")) {
            handleDeposit(player);
        } else if (action.startsWith("withdraw_")) {
            handleWithdraw(player, action.substring("withdraw_".length()));
        }
    }

    private void handleDeposit(Player player) {
        int deposited = cashManager.depositAll(player);
        if (deposited > 0) {
            player.sendMessage(ChatColor.GREEN + "Deposited $" + deposited + " in cash.");
        } else {
            player.sendMessage(ChatColor.RED + "You aren't carrying any cash.");
        }
        player.openInventory(bankMenu.build(player)); // refresh so the new balance shows immediately
    }

    private void handleWithdraw(Player player, String amountText) {
        int amount;
        try {
            amount = Integer.parseInt(amountText);
        } catch (NumberFormatException e) {
            return;
        }

        CashManager.Result result = cashManager.withdraw(player, amount);
        switch (result) {
            case SUCCESS -> player.sendMessage(ChatColor.GREEN + "Withdrew $" + amount + " as physical cash.");
            case INSUFFICIENT_FUNDS -> player.sendMessage(ChatColor.RED + "You don't have that much.");
            case NOT_WHOLE_DOLLARS -> player.sendMessage(ChatColor.RED + "Cash can only be withdrawn in whole dollars.");
            case INVENTORY_FULL -> player.sendMessage(ChatColor.RED + "Not enough inventory space for that many bills.");
        }
        player.openInventory(bankMenu.build(player)); // refresh so the new balance shows immediately
    }
}
