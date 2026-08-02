package com.nexusuniverse.economy.credit;

import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Soft, reflection-based link to NexusBudget's public NexusBudgetAPI service -- no compile-time
 * dependency on NexusBudget needed, and this plugin works fine without it installed at all
 * (/credit paybill just reports NexusBudget isn't available). Same reflective
 * ServicesManager-lookup pattern already used elsewhere in this plugin family (e.g. NexusLegends'
 * own BudgetBridge).
 *
 * Needs two NexusBudgetAPI methods that didn't exist before this session: getBillAmountOwed
 * (read-only lookup) and markExternalBillPaid (reduces a specific existing bill's amount due
 * WITHOUT touching Vault, since the money is coming from the player's credit account instead of
 * their bank balance) -- both added to NexusBudget alongside this.
 */
public class BudgetBridge {

    private static final String API_CLASS_NAME = "com.nexus.budget.api.NexusBudgetAPI";

    private Object api;
    private Method getBillAmountOwedMethod;
    private Method markExternalBillPaidMethod;

    public boolean isConnected() {
        if (api == null) tryConnect();
        return api != null;
    }

    private void tryConnect() {
        try {
            Class<?> apiClass = Class.forName(API_CLASS_NAME);
            RegisteredServiceProvider<?> provider = Bukkit.getServicesManager().getRegistration(apiClass);
            if (provider == null) return;

            this.api = provider.getProvider();
            this.getBillAmountOwedMethod = apiClass.getMethod("getBillAmountOwed", UUID.class, String.class);
            this.markExternalBillPaidMethod = apiClass.getMethod("markExternalBillPaid", UUID.class, String.class, double.class);
        } catch (ReflectiveOperationException | NoClassDefFoundError ignored) {
            // NexusBudget isn't installed, or predates these two methods -- stay disconnected
        }
    }

    /** The amount currently owed on the given player's bill matching this id/prefix, or -1 if no matching bill exists (or NexusBudget isn't connected). */
    public double getBillAmountOwed(UUID playerId, String billIdPrefix) {
        if (!isConnected()) return -1;
        try {
            return (double) getBillAmountOwedMethod.invoke(api, playerId, billIdPrefix);
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    /** Marks the given amount paid toward that bill, without touching Vault. Returns the amount actually applied, or -1 if nothing matched. */
    public double markExternalBillPaid(UUID playerId, String billIdPrefix, double amount) {
        if (!isConnected()) return -1;
        try {
            return (double) markExternalBillPaidMethod.invoke(api, playerId, billIdPrefix, amount);
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }
}
