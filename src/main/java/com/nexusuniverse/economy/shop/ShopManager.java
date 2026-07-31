package com.nexusuniverse.economy.shop;

import com.nexusuniverse.economy.AccountManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Loads shop-items.yml into in-memory categories, builds the
 * category-list and per-category paginated GUIs, and runs every
 * buy/sell transaction against the Bank (AccountManager) -- nothing
 * here keeps its own separate money.
 *
 * shop-items.yml ships as a small, original starter catalog (a simple
 * five-tier rarity formula, not data from any other plugin) -- meant to
 * be edited and expanded directly, which is also why buy/sell here are
 * plain per-item numbers rather than anything derived from a third
 * party's configuration.
 *
 * On top of that hand-written catalog, {@link #generateAutoBlocks()}
 * walks every {@link Material} the server knows about and adds every
 * remaining placeable block into the "Blocks" tab automatically, so the
 * shop always covers the full block list for whatever Minecraft version
 * the server is running -- instead of a hand-typed list that would go
 * stale (and be error-prone) the moment a new version adds blocks.
 */
public class ShopManager {

    private static final int ITEMS_PER_PAGE = 45;
    private static final String CUSTOM_KEY = "Custom";

    private final Plugin plugin;
    private final AccountManager accounts;
    private final NamespacedKey categoryKeyTag;
    private final Map<String, ShopCategory> categories = new LinkedHashMap<>();
    private final File customItemsFile;
    private final List<CustomShopEntry> customItems = new ArrayList<>();
    private final Map<UUID, ShopMenuHolder.Mode> lastMode = new HashMap<>();

    public ShopManager(Plugin plugin, AccountManager accounts) {
        this.plugin = plugin;
        this.accounts = accounts;
        this.categoryKeyTag = new NamespacedKey(plugin, "shop_category_key");
        this.customItemsFile = new File(plugin.getDataFolder(), "custom-items.yml");
        load();
        generateAutoBlocks();
        loadCustomItems();
    }

    /** Remembers which side of the buy/sell toggle a player last had selected, so it carries over between menu opens. */
    public ShopMenuHolder.Mode lastMode(UUID uuid) {
        return lastMode.getOrDefault(uuid, ShopMenuHolder.Mode.BUY);
    }

    public void setLastMode(UUID uuid, ShopMenuHolder.Mode mode) {
        lastMode.put(uuid, mode);
    }

    public NamespacedKey categoryKeyTag() {
        return categoryKeyTag;
    }

    private void load() {
        File file = new File(plugin.getDataFolder(), "shop-items.yml");
        if (!file.exists()) {
            plugin.saveResource("shop-items.yml", false);
        }

        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        double buyMultiplier = plugin.getConfig().getDouble("shop.buy-price-multiplier", 1.0);
        double sellMultiplier = plugin.getConfig().getDouble("shop.sell-price-multiplier", 1.0);

        ConfigurationSection categoriesSection = data.getConfigurationSection("categories");
        if (categoriesSection == null) return;

        for (String key : categoriesSection.getKeys(false)) {
            ConfigurationSection catSection = categoriesSection.getConfigurationSection(key);
            if (catSection == null) continue;

            String displayName = catSection.getString("display-name", key);
            Material icon = parseMaterial(catSection.getString("icon", "CHEST"));

            List<ShopItem> items = new ArrayList<>();
            for (Map<?, ?> map : catSection.getMapList("items")) {
                Material material = parseMaterial(String.valueOf(map.get("material")));
                if (material == null) continue;
                double buy = round2(toDouble(map.get("buy")) * buyMultiplier);
                double sellRaw = toDouble(map.get("sell"));
                double sell = sellRaw > 0 ? round2(sellRaw * sellMultiplier) : sellRaw;
                items.add(new ShopItem(material, buy, sell));
            }

            categories.put(key, new ShopCategory(key, displayName, icon != null ? icon : Material.CHEST, items));
        }

        plugin.getLogger().info("NexusEconomy: loaded " + categories.size() + " shop categories, "
                + categories.values().stream().mapToInt(c -> c.items().size()).sum() + " items total.");
    }

    private Material parseMaterial(String name) {
        try {
            return Material.valueOf(name);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("NexusEconomy: unknown material in shop-items.yml: " + name);
            return null;
        }
    }

    private double toDouble(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public Map<String, ShopCategory> categories() {
        return categories;
    }

    /**
     * Adds every remaining placeable block in the game to the "Blocks" tab
     * (creating that tab if shop-items.yml didn't define it). Anything
     * already listed by hand anywhere in shop-items.yml is skipped, so
     * curated entries (ores, decoration, etc. with their own tuned prices)
     * are never duplicated.
     *
     * A material only counts as an "obtainable block" if Bukkit reports
     * both isBlock() and isItem() -- that naturally excludes fluids, fire,
     * wall-attached variants (torches, heads, etc. -- those are already
     * covered by their item form), piston internals, and other things that
     * can't exist as a real ItemStack. isLegacy() is also excluded so old
     * pre-1.13 aliases in the enum don't sneak in as duplicates.
     *
     * A short default exclude list is applied on top of that -- the two
     * blocks the person running the server explicitly asked to leave out
     * (respawn anchors and TNT), plus a few blocks that are technical/
     * creative-mode tools rather than survival items (command blocks,
     * structure/jigsaw blocks, barrier, spawner, bedrock). Giving those out
     * would be a much bigger "ruin the server" risk than TNT, so they're
     * withheld by default too -- all of this is just config, so any of it
     * can be re-enabled or added to in config.yml.
     */
    private void generateAutoBlocks() {
        if (!plugin.getConfig().getBoolean("shop.auto-blocks.enabled", true)) return;

        String categoryName = plugin.getConfig().getString("shop.auto-blocks.category-name", "Blocks");
        double defaultBuy = plugin.getConfig().getDouble("shop.auto-blocks.default-buy", 2.00);
        double defaultSell = plugin.getConfig().getDouble("shop.auto-blocks.default-sell", 1.00);
        double buyMultiplier = plugin.getConfig().getDouble("shop.buy-price-multiplier", 1.0);
        double sellMultiplier = plugin.getConfig().getDouble("shop.sell-price-multiplier", 1.0);

        Set<Material> excluded = EnumSet.noneOf(Material.class);
        for (String name : plugin.getConfig().getStringList("shop.auto-blocks.excluded")) {
            Material material = parseMaterial(name.trim());
            if (material != null) excluded.add(material);
        }

        ConfigurationSection overridesSection = plugin.getConfig().getConfigurationSection("shop.auto-blocks.price-overrides");

        // Every material already hand-listed anywhere in shop-items.yml -- never duplicate these.
        Set<Material> alreadyListed = new HashSet<>();
        for (ShopCategory category : categories.values()) {
            for (ShopItem item : category.items()) {
                alreadyListed.add(item.material());
            }
        }

        List<ShopItem> autoItems = new ArrayList<>();
        for (Material material : Material.values()) {
            if (!material.isBlock() || !material.isItem() || material.isLegacy()) continue;
            if (excluded.contains(material) || alreadyListed.contains(material)) continue;

            double buy = defaultBuy;
            double sell = defaultSell;
            if (overridesSection != null) {
                ConfigurationSection override = overridesSection.getConfigurationSection(material.name());
                if (override != null) {
                    buy = override.getDouble("buy", defaultBuy);
                    sell = override.getDouble("sell", defaultSell);
                }
            }
            buy = round2(buy * buyMultiplier);
            sell = sell > 0 ? round2(sell * sellMultiplier) : sell;
            autoItems.add(new ShopItem(material, buy, sell));
        }

        autoItems.sort((a, b) -> a.material().name().compareTo(b.material().name()));

        ShopCategory existing = categories.get(categoryName);
        if (existing == null) {
            categories.put(categoryName, new ShopCategory(categoryName, categoryName, Material.GRASS_BLOCK, autoItems));
        } else {
            List<ShopItem> merged = new ArrayList<>(existing.items());
            merged.addAll(autoItems);
            categories.put(categoryName, new ShopCategory(existing.key(), existing.displayName(), existing.icon(), merged));
        }

        plugin.getLogger().info("NexusEconomy: auto-added " + autoItems.size() + " blocks to the \"" + categoryName + "\" tab ("
                + excluded.size() + " excluded, " + alreadyListed.size() + " already hand-listed elsewhere).");
    }

    // --- Custom items (admin-added, buy-only, arbitrary ItemStacks with their own NBT) ---

    private void loadCustomItems() {
        if (!customItemsFile.exists()) return;
        YamlConfiguration data = YamlConfiguration.loadConfiguration(customItemsFile);
        ConfigurationSection section = data.getConfigurationSection("items");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            ItemStack item = section.getItemStack(key + ".item");
            if (item == null) continue;
            double buy = section.getDouble(key + ".buy");
            customItems.add(new CustomShopEntry(item, buy));
        }
    }

    private void saveCustomItems() {
        YamlConfiguration data = new YamlConfiguration();
        for (int i = 0; i < customItems.size(); i++) {
            CustomShopEntry entry = customItems.get(i);
            data.set("items." + i + ".item", entry.item());
            data.set("items." + i + ".buy", entry.buy());
        }
        try {
            data.save(customItemsFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "NexusEconomy: failed to save custom-items.yml", e);
        }
    }

    /** Registers whatever's in the admin's hand as a new Custom-tab entry. */
    public void addCustomItem(ItemStack item, double buy) {
        customItems.add(new CustomShopEntry(item.clone(), buy));
        saveCustomItems();
    }

    public boolean removeCustomItem(int index) {
        if (index < 0 || index >= customItems.size()) return false;
        customItems.remove(index);
        saveCustomItems();
        return true;
    }

    public List<CustomShopEntry> customItems() {
        return customItems;
    }

    // --- GUI building ---

    public Inventory buildCategoryMenu() {
        int totalButtons = categories.size() + 1; // +1 for the always-shown Custom button
        int size = Math.min(54, Math.max(9, ((totalButtons + 8) / 9) * 9));
        ShopMenuHolder holder = new ShopMenuHolder(ShopMenuHolder.Type.CATEGORY_LIST, null, 0);
        Inventory inv = Bukkit.createInventory(holder, size, ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "Shop Categories");
        holder.setInventory(inv);

        for (ShopCategory category : categories.values()) {
            ItemStack icon = new ItemStack(category.icon());
            ItemMeta meta = icon.getItemMeta();
            meta.setDisplayName(ChatColor.GREEN + category.displayName());
            meta.setLore(List.of(ChatColor.GRAY + "" + category.items().size() + " items"));
            meta.getPersistentDataContainer().set(categoryKeyTag, PersistentDataType.STRING, category.key());
            icon.setItemMeta(meta);
            inv.addItem(icon);
        }

        ItemStack customIcon = new ItemStack(Material.NETHER_STAR);
        ItemMeta customMeta = customIcon.getItemMeta();
        customMeta.setDisplayName(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Custom");
        customMeta.setLore(List.of(ChatColor.GRAY + "" + customItems.size() + " items", ChatColor.DARK_GRAY + "Server-added specials"));
        customMeta.getPersistentDataContainer().set(categoryKeyTag, PersistentDataType.STRING, CUSTOM_KEY);
        customIcon.setItemMeta(customMeta);
        inv.addItem(customIcon);

        return inv;
    }

    public Inventory buildCategoryPage(String categoryKey, int page) {
        return buildCategoryPage(categoryKey, page, ShopMenuHolder.Mode.BUY);
    }

    public Inventory buildCategoryPage(String categoryKey, int page, ShopMenuHolder.Mode mode) {
        ShopCategory category = categories.get(categoryKey);
        if (category == null) return null;

        List<ShopItem> items = category.items();
        int totalPages = Math.max(1, (items.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        page = Math.max(0, Math.min(page, totalPages - 1));
        if (mode == null) mode = ShopMenuHolder.Mode.BUY;

        ShopMenuHolder holder = new ShopMenuHolder(ShopMenuHolder.Type.ITEM_PAGE, categoryKey, page, mode);
        Inventory inv = Bukkit.createInventory(holder, 54, ChatColor.DARK_GREEN + "" + ChatColor.BOLD + category.displayName()
                + ChatColor.GRAY + " (" + (page + 1) + "/" + totalPages + ")");
        holder.setInventory(inv);

        int start = page * ITEMS_PER_PAGE;
        int end = Math.min(items.size(), start + ITEMS_PER_PAGE);
        for (int i = start; i < end; i++) {
            ShopItem shopItem = items.get(i);
            ItemStack display = new ItemStack(shopItem.material());
            ItemMeta meta = display.getItemMeta();
            meta.setDisplayName(ChatColor.WHITE + prettyName(shopItem.material()));

            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GREEN + "Buy: $" + String.format("%.2f", shopItem.buy())
                    + ChatColor.GRAY + (mode == ShopMenuHolder.Mode.BUY ? " (click)" : ""));
            if (shopItem.sellable()) {
                lore.add(ChatColor.GOLD + "Sell: $" + String.format("%.2f", shopItem.sell())
                        + ChatColor.GRAY + (mode == ShopMenuHolder.Mode.SELL ? " (click)" : " (right-click)"));
            } else {
                lore.add(ChatColor.DARK_GRAY + "Not sellable");
            }
            lore.add(ChatColor.DARK_GRAY + "Shift-click for a stack (64)");
            meta.setLore(lore);
            display.setItemMeta(meta);
            inv.setItem(i - start, display);
        }

        if (page > 0) inv.setItem(45, navItem(Material.ARROW, "Previous Page"));
        inv.setItem(49, navItem(Material.BARRIER, "Back to Categories"));
        if (page < totalPages - 1) inv.setItem(52, navItem(Material.ARROW, "Next Page"));
        inv.setItem(53, modeToggleItem(mode));

        return inv;
    }

    /**
     * The buy/sell toggle in the bottom-right corner. A plain click on any
     * item in the grid always performs whatever mode this is currently
     * set to -- meant for controller/Xbox players, who only have one click
     * and can't right-click to sell. Right-click on an item still always
     * sells directly, unaffected by this, so mouse players keep that
     * shortcut too.
     */
    private ItemStack modeToggleItem(ShopMenuHolder.Mode mode) {
        boolean buying = mode == ShopMenuHolder.Mode.BUY;
        ItemStack item = new ItemStack(buying ? Material.LIME_DYE : Material.RED_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName((buying ? ChatColor.GREEN : ChatColor.GOLD) + "" + ChatColor.BOLD
                + "Mode: " + (buying ? "BUYING" : "SELLING"));
        meta.setLore(List.of(
                ChatColor.GRAY + "Click an item to " + (buying ? "buy" : "sell") + " it.",
                ChatColor.GRAY + "Click here to switch to " + (buying ? "selling" : "buying") + "."
        ));
        item.setItemMeta(meta);
        return item;
    }

    public Inventory buildCustomPage(int page) {
        int totalPages = Math.max(1, (customItems.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        page = Math.max(0, Math.min(page, totalPages - 1));

        ShopMenuHolder holder = new ShopMenuHolder(ShopMenuHolder.Type.CUSTOM_PAGE, CUSTOM_KEY, page);
        Inventory inv = Bukkit.createInventory(holder, 54, ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Custom"
                + ChatColor.GRAY + " (" + (page + 1) + "/" + totalPages + ")");
        holder.setInventory(inv);

        int start = page * ITEMS_PER_PAGE;
        int end = Math.min(customItems.size(), start + ITEMS_PER_PAGE);
        for (int i = start; i < end; i++) {
            CustomShopEntry entry = customItems.get(i);
            ItemStack display = entry.item().clone();
            ItemMeta meta = display.getItemMeta();

            List<String> lore = new ArrayList<>(meta.hasLore() && meta.getLore() != null ? meta.getLore() : List.of());
            lore.add("");
            lore.add(ChatColor.GREEN + "Buy: $" + String.format("%.2f", entry.buy()) + ChatColor.GRAY + " (left-click)");
            lore.add(ChatColor.DARK_GRAY + "Not sellable back to the shop");
            meta.setLore(lore);
            display.setItemMeta(meta);
            inv.setItem(i - start, display);
        }

        if (page > 0) inv.setItem(45, navItem(Material.ARROW, "Previous Page"));
        inv.setItem(49, navItem(Material.BARRIER, "Back to Categories"));
        if (page < totalPages - 1) inv.setItem(53, navItem(Material.ARROW, "Next Page"));

        return inv;
    }

    private ItemStack navItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.YELLOW + name);
        item.setItemMeta(meta);
        return item;
    }

    private String prettyName(Material material) {
        String raw = material.name().toLowerCase().replace('_', ' ');
        StringBuilder sb = new StringBuilder();
        for (String word : raw.split(" ")) {
            if (!word.isEmpty()) sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }

    // --- Transactions ---

    public boolean buy(Player player, ShopItem item, int quantity) {
        double cost = round2(item.buy() * quantity);
        if (!accounts.has(player.getUniqueId(), cost)) return false;
        if (!hasRoomFor(player, item.material())) return false;

        accounts.withdraw(player.getUniqueId(), cost);
        player.getInventory().addItem(new ItemStack(item.material(), quantity));
        return true;
    }

    public boolean buyCustom(Player player, CustomShopEntry entry, int quantity) {
        double cost = round2(entry.buy() * quantity);
        if (!accounts.has(player.getUniqueId(), cost)) return false;

        ItemStack toGive = entry.item().clone();
        toGive.setAmount(quantity);
        var leftover = player.getInventory().addItem(toGive);
        if (!leftover.isEmpty()) {
            player.getInventory().removeItem(toGive); // undo the partial add -- all or nothing
            return false;
        }

        accounts.withdraw(player.getUniqueId(), cost);
        return true;
    }

    public int sell(Player player, ShopItem item, int quantity) {
        if (!item.sellable()) return 0;
        int available = countInInventory(player, item.material());
        int toSell = Math.min(available, quantity);
        if (toSell <= 0) return 0;

        removeFromInventory(player, item.material(), toSell);
        double payout = round2(item.sell() * toSell);
        accounts.createAccount(player.getUniqueId());
        accounts.deposit(player.getUniqueId(), payout);
        return toSell;
    }

    private boolean hasRoomFor(Player player, Material material) {
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()) return true;
            if (stack.getType() == material && stack.getAmount() < stack.getMaxStackSize()) return true;
        }
        return false;
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
}
