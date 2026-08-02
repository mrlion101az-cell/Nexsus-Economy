package com.nexusuniverse.economy.credit;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Broadcasts a reminder of how to get a physical credit card (/credit give) on a configurable
 * interval -- purely a chat message, doesn't touch any account. Real interval changes need
 * /nexuseconomy reload or a restart to take effect, since the task is scheduled once at the
 * interval read at startup; toggling credit.announcement.enabled off and reloading stops it from
 * firing again, but an already-scheduled task keeps its original period until then.
 */
public class CreditAnnouncer {

    private final Plugin plugin;
    private BukkitTask task;

    public CreditAnnouncer(Plugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("credit.announcement.enabled", true)) return;

        int intervalMinutes = Math.max(1, plugin.getConfig().getInt("credit.announcement.interval-minutes", 20));
        long intervalTicks = 20L * 60 * intervalMinutes;

        task = Bukkit.getScheduler().runTaskTimer(plugin, this::announce, intervalTicks, intervalTicks);
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    private void announce() {
        String message = plugin.getConfig().getString("credit.announcement.message",
                "&b&lNeed cash on hand? &fRun &e/credit give &fto get a credit card, then &e/credit charge <amount> &fany time.");
        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', message));
    }
}
