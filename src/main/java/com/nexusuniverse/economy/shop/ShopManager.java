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
 * remaining placeable block, sorted across the shop's tabs by name
 * pattern, so the shop always covers the full block list for whatever
 * Minecraft version the server is running -- instead of a hand-typed
 * list that would go stale (and be error-prone) the moment a new
 * version adds blocks.
 */
public class ShopManager {

    private static final int ITEMS_PER_PAGE = 45;
    private static final String CUSTOM_KEY = "Custom";
    // Bumped whenever shop-items.yml's bundled content changes in a way that needs to reach
    // servers that already have an old copy on disk -- see the version check in load().
    private static final int CATALOG_VERSION = 2;

    private final Plugin plugin;
    private final AccountManager accounts;
    private final NamespacedKey categoryKeyTag;
    private final Map<String, ShopCategory> categories = new LinkedHashMap<>();
    private final File customItemsFile;
    private final List<CustomShopEntry> customItems = new ArrayList<>();
    private final Map<UUID, ShopMenuHolder.Mode> lastMode = new HashMap<>();
    private final Map<String, double[]> pricingTiers = new LinkedHashMap<>();

    public ShopManager(Plugin plugin, AccountManager accounts) {
        this.plugin = plugin;
        this.accounts = accounts;
        this.categoryKeyTag = new NamespacedKey(plugin, "shop_category_key");
        this.customItemsFile = new File(plugin.getDataFolder(), "custom-items.yml");
        loadPricingTiers();
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

    /**
     * Loads the shared rarity/complexity price table from
     * shop.pricing-tiers in config.yml -- SCRAP through MYTHIC, each with
     * its own buy and sell price. Both shop-items.yml (via the "tier:"
     * field) and the auto-generated block catalog resolve their prices
     * from this one table, so retuning a tier in config.yml re-prices
     * every item that uses it at once instead of needing to hand-edit
     * hundreds of individual entries.
     *
     * Buy is priced well above sell on every tier -- deliberately, per
     * how this shop's meant to work: selling is where players make real
     * money (especially on the higher tiers), while buying something
     * outright instead of earning/finding it is a genuine money sink.
     */
    private void loadPricingTiers() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("shop.pricing-tiers");
        if (section == null) {
            plugin.getLogger().warning("NexusEconomy: no shop.pricing-tiers in config.yml -- tiered items will fall back to $2/$1.");
            return;
        }
        for (String tierName : section.getKeys(false)) {
            ConfigurationSection tier = section.getConfigurationSection(tierName);
            if (tier == null) continue;
            double buy = tier.getDouble("buy", 2.00);
            double sell = tier.getDouble("sell", 1.00);
            pricingTiers.put(tierName.toUpperCase(java.util.Locale.ROOT), new double[]{buy, sell});
        }
    }

    /** Looks up a tier's {buy, sell} pair, warning and falling back to the cheapest sane default if the name doesn't exist. */
    private double[] resolveTier(String tierName) {
        double[] prices = pricingTiers.get(tierName == null ? null : tierName.toUpperCase(java.util.Locale.ROOT));
        if (prices == null) {
            plugin.getLogger().warning("NexusEconomy: unknown pricing tier \"" + tierName + "\", defaulting to $2/$1.");
            return new double[]{2.00, 1.00};
        }
        return prices;
    }

    private void load() {
        File file = new File(plugin.getDataFolder(), "shop-items.yml");
        if (!file.exists()) {
            plugin.saveResource("shop-items.yml", false);
        } else {
            // shop-items.yml isn't a Bukkit Configuration, so it doesn't get the automatic
            // copyDefaults() merge that config.yml gets in NexusEconomyPlugin#onEnable -- without
            // this check, a server that already has a copy on disk from an older plugin version
            // would keep using its old prices forever, even after an update changes the bundled
            // catalog (which is exactly what happened between the "tier:" pricing rework landing
            // in code and it actually reaching a live server). If the on-disk file is older than
            // what this build ships, back it up (nothing is silently lost) and pull the fresh one.
            YamlConfiguration onDisk = YamlConfiguration.loadConfiguration(file);
            int onDiskVersion = onDisk.getInt("catalog-version", 0);
            if (onDiskVersion < CATALOG_VERSION) {
                File backup = new File(plugin.getDataFolder(), "shop-items.yml.v" + onDiskVersion + ".bak");
                if (file.renameTo(backup)) {
                    plugin.getLogger().warning("NexusEconomy: shop-items.yml on disk was catalog version "
                            + onDiskVersion + ", this build ships version " + CATALOG_VERSION
                            + " -- backed up your old copy to " + backup.getName() + " and installed the new one. "
                            + "If you'd hand-edited shop-items.yml, re-apply those changes from the backup.");
                    plugin.saveResource("shop-items.yml", false);
                } else {
                    plugin.getLogger().warning("NexusEconomy: shop-items.yml on disk is catalog version "
                            + onDiskVersion + " but this build ships version " + CATALOG_VERSION
                            + ", and backing it up failed -- using the old file as-is. Delete or rename "
                            + "shop-items.yml and restart to pick up the new catalog.");
                }
            }
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

                double buy;
                double sellRaw;
                String tier = map.get("tier") == null ? null : String.valueOf(map.get("tier"));
                if (tier != null) {
                    double[] prices = resolveTier(tier);
                    buy = prices[0];
                    sellRaw = prices[1];
                } else {
                    // No tier -- a genuine one-off price (trophies, buy-only specials like
                    // Nether Star/Beacon, or anything hand-priced outside the tier ladder).
                    buy = toDouble(map.get("buy"));
                    sellRaw = toDouble(map.get("sell"));
                }

                buy = round2(buy * buyMultiplier);
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
     * Adds every remaining placeable block in the game across the shop's
     * tabs -- Ores, Redstone, Farming, Decoration, Workstations, plus new
     * "Wood &amp; Building", "Stone &amp; Building", "Colored Blocks", and
     * "Nether &amp; End" tabs, with "Blocks" holding generic terrain and
     * "Miscellaneous" as the last-resort catch-all. Anything already listed
     * by hand anywhere in shop-items.yml is skipped, so curated entries
     * keep their own tuned prices and nothing is duplicated.
     *
     * Version 0.8.0 dumped every auto-added block into one "Blocks" tab.
     * On a current Minecraft version that's genuinely ~900+ distinct block
     * materials once every wood species, stone type, and dye color is
     * counted separately -- not a bug, just how many blocks the game
     * actually has -- but a single tab that size is unusable and, worse,
     * one bad material in that huge a batch could throw an exception and
     * take the whole page down with it. {@link #classifyAutoBlock(Material)}
     * sorts the list into the tabs above by name pattern, and every item
     * built in {@link #buildCategoryPage} is now wrapped so one broken
     * entry gets skipped (and logged) instead of breaking the page.
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
     * (respawn anchors and TNT), plus a handful of blocks that are
     * technical/creative-mode tools rather than survival items (command
     * blocks, structure/jigsaw blocks, barrier, spawner/trial spawner,
     * vault, bedrock). Giving those out would be a much bigger "ruin the
     * server" risk than TNT, so they're withheld by default too -- all of
     * this is just config, so any of it can be re-enabled or added to.
     *
     * The classifier is a best-effort heuristic based on material name
     * patterns, not a perfect ontology -- anything that lands in the wrong
     * tab can be pinned to a specific tab with
     * shop.auto-blocks.category-overrides in config.yml.
     *
     * Pricing for every auto-added block comes from the shared
     * shop.pricing-tiers table (see {@link #loadPricingTiers()}), resolved
     * in priority order: an exact $ pin in price-overrides, then a
     * per-material tier pin in tier-overrides, then the tab it landed in's
     * default tier in tab-default-tier, then COMMON if none of those match.
     */
    private void generateAutoBlocks() {
        if (!plugin.getConfig().getBoolean("shop.auto-blocks.enabled", true)) return;

        double buyMultiplier = plugin.getConfig().getDouble("shop.buy-price-multiplier", 1.0);
        double sellMultiplier = plugin.getConfig().getDouble("shop.sell-price-multiplier", 1.0);

        Set<Material> excluded = EnumSet.noneOf(Material.class);
        for (String name : plugin.getConfig().getStringList("shop.auto-blocks.excluded")) {
            Material material = parseMaterial(name.trim());
            if (material != null) excluded.add(material);
        }

        ConfigurationSection priceOverridesSection = plugin.getConfig().getConfigurationSection("shop.auto-blocks.price-overrides");
        ConfigurationSection tierOverridesSection = plugin.getConfig().getConfigurationSection("shop.auto-blocks.tier-overrides");
        ConfigurationSection categoryOverridesSection = plugin.getConfig().getConfigurationSection("shop.auto-blocks.category-overrides");
        ConfigurationSection tabDefaultTierSection = plugin.getConfig().getConfigurationSection("shop.auto-blocks.tab-default-tier");

        // Every material already hand-listed anywhere in shop-items.yml -- never duplicate these.
        Set<Material> alreadyListed = new HashSet<>();
        for (ShopCategory category : categories.values()) {
            for (ShopItem item : category.items()) {
                alreadyListed.add(item.material());
            }
        }

        Map<String, List<ShopItem>> buckets = new LinkedHashMap<>();
        int totalAdded = 0;

        for (Material material : Material.values()) {
            try {
                if (!material.isBlock() || !material.isItem() || material.isLegacy()) continue;
                if (excluded.contains(material) || alreadyListed.contains(material)) continue;

                String bucket = categoryOverridesSection != null
                        ? categoryOverridesSection.getString(material.name(), classifyAutoBlock(material))
                        : classifyAutoBlock(material);

                double buy;
                double sell;
                ConfigurationSection exact = priceOverridesSection == null ? null : priceOverridesSection.getConfigurationSection(material.name());
                if (exact != null) {
                    // An exact $ pin always wins -- for true anchors that don't fit a tier cleanly.
                    buy = exact.getDouble("buy", 2.00);
                    sell = exact.getDouble("sell", 1.00);
                } else {
                    String tier = tierOverridesSection != null ? tierOverridesSection.getString(material.name()) : null;
                    if (tier == null) {
                        tier = tabDefaultTierSection != null ? tabDefaultTierSection.getString(bucket) : null;
                    }
                    double[] prices = resolveTier(tier != null ? tier : "COMMON");
                    buy = prices[0];
                    sell = prices[1];
                }

                buy = round2(buy * buyMultiplier);
                sell = sell > 0 ? round2(sell * sellMultiplier) : sell;

                buckets.computeIfAbsent(bucket, b -> new ArrayList<>()).add(new ShopItem(material, buy, sell));
                totalAdded++;
            } catch (Exception e) {
                // Same defensive principle as the GUI-building code below: one odd material
                // shouldn't stop the whole shop from loading.
                plugin.getLogger().log(Level.WARNING, "NexusEconomy: couldn't auto-catalog " + material + ", skipping it.", e);
            }
        }

        for (Map.Entry<String, List<ShopItem>> entry : buckets.entrySet()) {
            String bucketName = entry.getKey();
            List<ShopItem> autoItems = entry.getValue();
            autoItems.sort((a, b) -> a.material().name().compareTo(b.material().name()));

            ShopCategory existing = categories.get(bucketName);
            if (existing == null) {
                categories.put(bucketName, new ShopCategory(bucketName, bucketName, autoCategoryIcon(bucketName), autoItems));
            } else {
                List<ShopItem> merged = new ArrayList<>(existing.items());
                merged.addAll(autoItems);
                categories.put(bucketName, new ShopCategory(existing.key(), existing.displayName(), existing.icon(), merged));
            }
        }

        plugin.getLogger().info("NexusEconomy: auto-added " + totalAdded + " blocks across " + buckets.size()
                + " tabs (" + excluded.size() + " excluded, " + alreadyListed.size() + " already hand-listed elsewhere).");

        // A concrete, checkable line for the exact "is this actually the price I think it is"
        // question -- read straight from the finished catalog, not recomputed separately, so if
        // this log line shows the right numbers the shop GUI will too. If it's still showing old
        // numbers after a restart, the server is running an old jar, not new prices that failed.
        logSamplePrice("REDSTONE_LAMP");
        logSamplePrice("DAYLIGHT_DETECTOR");
        logSamplePrice("RAIL");
    }

    private void logSamplePrice(String materialName) {
        Material material = parseMaterial(materialName);
        if (material == null) return;
        for (ShopCategory category : categories.values()) {
            for (ShopItem item : category.items()) {
                if (item.material() == material) {
                    plugin.getLogger().info("NexusEconomy: sanity check -- " + materialName + " in \""
                            + category.displayName() + "\": buy $" + String.format("%.2f", item.buy())
                            + " / sell $" + String.format("%.2f", item.sell()));
                    return;
                }
            }
        }
        plugin.getLogger().info("NexusEconomy: sanity check -- " + materialName + " isn't in the shop at all right now.");
    }

    // Wood species/build-piece tokens that route a material into "Wood & Building".
    private static final List<String> WOOD_TOKENS = List.of(
            "OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA", "DARK_OAK", "MANGROVE",
            "CHERRY", "BAMBOO", "CRIMSON", "WARPED", "STRIPPED", "LADDER", "BOOKSHELF", "SCAFFOLDING");

    // Stone/mineral tokens that route a material into "Stone & Building".
    private static final List<String> STONE_TOKENS = List.of(
            "STONE", "COBBLESTONE", "DEEPSLATE", "TUFF", "GRANITE", "DIORITE", "ANDESITE",
            "CALCITE", "DRIPSTONE", "SANDSTONE", "BRICK", "MUD", "PRISMARINE", "GLASS");

    // Nether/End specific terrain -- checked before the wood/stone buckets so things like
    // CRIMSON_NYLIUM (which also contains the wood token "CRIMSON") land here instead.
    private static final List<String> NETHER_END_TOKENS = List.of(
            "NETHERRACK", "SOUL_SAND", "SOUL_SOIL", "BASALT", "BLACKSTONE", "END_STONE",
            "PURPUR", "MAGMA", "SHROOMLIGHT", "WART", "CHORUS", "NYLIUM", "FUNGUS", "ROOTS", "GLOWSTONE");

    // Dyed-block suffixes that route a material into "Colored Blocks", regardless of color prefix.
    private static final List<String> COLORED_SUFFIXES = List.of(
            "_WOOL", "_CARPET", "_CONCRETE", "_CONCRETE_POWDER", "_TERRACOTTA",
            "_STAINED_GLASS", "_STAINED_GLASS_PANE", "_BANNER", "_BED", "_CANDLE", "_SHULKER_BOX");

    private static final List<String> REDSTONE_TOKENS = List.of(
            "REDSTONE", "REPEATER", "COMPARATOR", "PISTON", "OBSERVER", "HOPPER", "DISPENSER",
            "DROPPER", "TARGET", "LECTERN", "NOTE_BLOCK", "TRIPWIRE", "DAYLIGHT_DETECTOR", "RAIL", "LEVER");

    private static final List<String> WORKSTATION_TOKENS = List.of(
            "CRAFTING_TABLE", "FURNACE", "SMOKER", "CARTOGRAPHY_TABLE", "FLETCHING_TABLE", "GRINDSTONE",
            "LOOM", "STONECUTTER", "BREWING_STAND", "COMPOSTER", "BARREL", "SMITHING_TABLE",
            "ENCHANTING_TABLE", "ANVIL", "BEACON");

    private static final List<String> FARMING_TOKENS = List.of(
            "SAPLING", "SUGAR_CANE", "CACTUS", "KELP", "SEAGRASS", "LILY_PAD", "VINE", "MOSS",
            "AZALEA", "MUSHROOM", "FLOWER", "TULIP", "ORCHID", "ALLIUM", "DANDELION", "POPPY",
            "CORNFLOWER", "LILAC", "PEONY", "SUNFLOWER", "FERN", "SEEDS", "GLOW_LICHEN",
            "DRIPLEAF", "PITCHER");

    private static final List<String> DECORATION_TOKENS = List.of(
            "LANTERN", "CANDLE", "CHAIN", "BARS", "FLOWER_POT", "CORAL", "SEA_PICKLE",
            "SCULK", "AMETHYST", "DECORATED_POT", "SKULL", "HEAD", "PANE", "END_ROD", "TORCH");

    // Generic terrain -- kept in the original "Blocks" tab.
    private static final List<String> TERRAIN_TOKENS = List.of(
            "DIRT", "GRASS_BLOCK", "PATH", "FARMLAND", "PODZOL", "MYCELIUM", "CLAY", "SAND",
            "GRAVEL", "SNOW", "ICE", "SPONGE", "SLIME", "HONEY", "HONEYCOMB");

    private static boolean hasToken(String name, String token) {
        return name.equals(token) || name.startsWith(token + "_") || name.endsWith("_" + token) || name.contains("_" + token + "_");
    }

    private static boolean hasAnyToken(String name, List<String> tokens) {
        for (String token : tokens) {
            if (hasToken(name, token)) return true;
        }
        return false;
    }

    private String classifyAutoBlock(Material material) {
        String name = material.name();

        if (hasAnyToken(name, NETHER_END_TOKENS)) return "Nether & End";
        if (name.equals("REDSTONE_BLOCK")) return "Redstone";
        if (name.endsWith("_ORE") || (name.endsWith("_BLOCK") && ORE_METAL_PREFIXES.contains(name.substring(0, name.length() - 6)))) return "Ores";
        if (COLORED_SUFFIXES.stream().anyMatch(name::endsWith)
                || name.equals("WOOL") || name.equals("TERRACOTTA") || name.equals("SHULKER_BOX")) return "Colored Blocks";
        if (hasAnyToken(name, WOOD_TOKENS) || name.contains("SIGN")) return "Wood & Building";
        if (hasAnyToken(name, STONE_TOKENS)) return "Stone & Building";
        if (hasAnyToken(name, REDSTONE_TOKENS)) return "Redstone";
        if (hasAnyToken(name, WORKSTATION_TOKENS)) return "Workstations";
        if (hasAnyToken(name, FARMING_TOKENS)) return "Farming";
        if (hasAnyToken(name, DECORATION_TOKENS)) return "Decoration";
        if (hasAnyToken(name, TERRAIN_TOKENS)) return "Blocks";

        return "Miscellaneous";
    }

    private static final Set<String> ORE_METAL_PREFIXES = Set.of(
            "COAL", "IRON", "GOLD", "DIAMOND", "EMERALD", "LAPIS", "COPPER", "NETHERITE",
            "QUARTZ", "AMETHYST", "RAW_IRON", "RAW_GOLD", "RAW_COPPER");

    private Material autoCategoryIcon(String bucketName) {
        return switch (bucketName) {
            case "Wood & Building" -> Material.OAK_PLANKS;
            case "Stone & Building" -> Material.STONE_BRICKS;
            case "Colored Blocks" -> Material.WHITE_WOOL;
            case "Nether & End" -> Material.NETHERRACK;
            case "Blocks" -> Material.GRASS_BLOCK;
            default -> Material.CHEST;
        };
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
            try {
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
            } catch (Exception e) {
                // One bad material shouldn't take the whole page down -- skip it and log
                // instead, so the tab still opens for everyone even if a future MC version
                // adds a material that doesn't build cleanly as an ItemStack.
                plugin.getLogger().log(Level.WARNING, "NexusEconomy: couldn't build shop tile for "
                        + shopItem.material() + " in \"" + categoryKey + "\", skipping it.", e);
            }
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
            try {
                ItemStack display = entry.item().clone();
                ItemMeta meta = display.getItemMeta();

                List<String> lore = new ArrayList<>(meta.hasLore() && meta.getLore() != null ? meta.getLore() : List.of());
                lore.add("");
                lore.add(ChatColor.GREEN + "Buy: $" + String.format("%.2f", entry.buy()) + ChatColor.GRAY + " (left-click)");
                lore.add(ChatColor.DARK_GRAY + "Not sellable back to the shop");
                meta.setLore(lore);
                display.setItemMeta(meta);
                inv.setItem(i - start, display);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "NexusEconomy: couldn't build Custom tab tile #" + i + ", skipping it.", e);
            }
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
