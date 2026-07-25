package com.nexusuniverse.economy.credit;

import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;

/**
 * Soft, reflection-based link to NexusSeasons -- a separate,
 * independently-built plugin, same pattern as NexusSurvival's
 * SeasonBridge -- used to detect "a new month has begun," so credit
 * billing cycles align with the server's in-game calendar.
 *
 * This tracks NexusSeasons' day-of-season counter directly and fires
 * the instant it rolls from its max value back to 1 -- not a
 * season-name comparison. With days-per-season at 30 (the default),
 * that IS a calendar month, exactly as described: day 30 rolls to day
 * 1, that's the start of the next billing cycle. Watching the day
 * counter itself, rather than inferring a change from the season name,
 * is what makes this correct rather than coincidentally correct --
 * it's tied to the actual thing that's supposed to trigger billing.
 *
 * If NexusSeasons isn't installed, isConnected() just stays false and
 * NexusEconomyPlugin falls back to a real-time billing interval
 * instead -- the credit system works fine either way.
 */
public class SeasonPoller {

    private static final String API_CLASS_NAME = "com.nexusuniverse.seasons.NexusSeasonsAPI";

    private Object api;
    private Method getDayOfSeasonMethod;
    private int lastDay = Integer.MIN_VALUE;

    public boolean isConnected() {
        if (api == null) tryConnect(); // NexusSeasons might enable after NexusEconomy did -- keep retrying lazily
        return api != null;
    }

    private void tryConnect() {
        try {
            Class<?> apiClass = Class.forName(API_CLASS_NAME);
            RegisteredServiceProvider<?> provider = Bukkit.getServicesManager().getRegistration(apiClass);
            if (provider == null) return;

            this.api = provider.getProvider();
            this.getDayOfSeasonMethod = apiClass.getMethod("getDayOfSeason");
        } catch (ReflectiveOperationException | NoClassDefFoundError ignored) {
            // NexusSeasons isn't installed -- stay disconnected
        }
    }

    /** Call periodically. Returns true exactly once per detected day-counter rollover to 1 (never on the very first check after startup). */
    public boolean checkForNewMonth() {
        if (!isConnected()) return false;
        try {
            int day = (int) getDayOfSeasonMethod.invoke(api);

            if (lastDay == Integer.MIN_VALUE) {
                lastDay = day;
                return false; // first observation just establishes a baseline, not a "change"
            }

            boolean rolledOver = day == 1 && lastDay != 1;
            lastDay = day;
            return rolledOver;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}

