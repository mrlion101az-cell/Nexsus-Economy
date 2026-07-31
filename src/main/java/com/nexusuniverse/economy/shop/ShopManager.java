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
    private static final int CATALOG_VERSION = 4;

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
     * tabs -- Ores, Redstone, Farming, Decoration, "Wood", "Stone",
     * "Colored Blocks", and "Nether &amp; End", with "Blocks" holding
     * generic terrain and "Miscellaneous" holding both true catch-all
     * leftovers and functional/use blocks (crafting tables, chests,
     * enchanting tables, and similar). Anything already listed by hand
     * anywhere in shop-items.yml is skipped, so curated entries keep
     * their own tuned prices and nothing is duplicated.
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
                    double[] varied = varyAutoPrice(material, bucket, prices[0], prices[1]);
                    buy = varied[0];
                    sell = varied[1];
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

    // Dedicated category classifier. Specific families are checked before broad
    // building-material families so (for example) stained glass goes to Glass,
    // carpets go to Carpets, and leaves/saplings never disappear into Wood.
    private static final List<String> WOOD_SPECIES = List.of(
            "OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA", "DARK_OAK", "MANGROVE", "CHERRY", "BAMBOO");

    private static final List<String> FLOWER_TOKENS = List.of(
            "DANDELION", "POPPY", "ORCHID", "ALLIUM", "AZURE_BLUET", "TULIP", "OXEYE_DAISY",
            "CORNFLOWER", "LILY_OF_THE_VALLEY", "WITHER_ROSE", "SUNFLOWER", "LILAC", "ROSE_BUSH",
            "PEONY", "TORCHFLOWER", "PITCHER_PLANT", "PINK_PETALS", "WILDFLOWERS", "EYEBLOSSOM");

    private static final List<String> PLANT_TOKENS = List.of(
            "GRASS", "FERN", "BUSH", "CACTUS", "SUGAR_CANE", "BAMBOO", "KELP", "SEAGRASS",
            "LILY_PAD", "VINE", "MOSS", "LICHEN", "DRIPLEAF", "ROOTS", "HANGING_ROOTS", "SPORE_BLOSSOM");

    private static final List<String> REDSTONE_TOKENS = List.of(
            "REDSTONE", "REPEATER", "COMPARATOR", "PISTON", "OBSERVER", "HOPPER", "DISPENSER",
            "DROPPER", "TARGET", "TRIPWIRE", "DAYLIGHT_DETECTOR", "RAIL", "LEVER", "PRESSURE_PLATE",
            "BUTTON", "LIGHTNING_ROD", "CRAFTER");

    private static final List<String> WORKSTATION_TOKENS = List.of(
            "CRAFTING_TABLE", "FURNACE", "SMOKER", "BLAST_FURNACE", "CARTOGRAPHY_TABLE",
            "FLETCHING_TABLE", "GRINDSTONE", "LOOM", "STONECUTTER", "BREWING_STAND", "COMPOSTER",
            "SMITHING_TABLE", "ENCHANTING_TABLE", "ANVIL", "LECTERN");

    private static final List<String> STORAGE_TOKENS = List.of(
            "CHEST", "BARREL", "SHULKER_BOX", "BUNDLE", "ENDER_CHEST", "DECORATED_POT");

    private static final List<String> LIGHTING_TOKENS = List.of(
            "TORCH", "LANTERN", "CANDLE", "GLOWSTONE", "SEA_LANTERN", "SHROOMLIGHT", "FROGLIGHT",
            "END_ROD", "OCHRE_FROGLIGHT", "PEARLESCENT_FROGLIGHT", "VERDANT_FROGLIGHT");

    private static final List<String> TERRAIN_TOKENS = List.of(
            "DIRT", "GRASS_BLOCK", "PATH", "FARMLAND", "PODZOL", "MYCELIUM", "CLAY", "SAND",
            "GRAVEL", "SNOW", "ICE", "SPONGE", "SLIME", "HONEY", "HONEYCOMB", "MUD");

    private static boolean hasToken(String name, String token) {
        return name.equals(token) || name.startsWith(token + "_") || name.endsWith("_" + token) || name.contains("_" + token + "_");
    }

    private static boolean hasAnyToken(String name, List<String> tokens) {
        for (String token : tokens) if (hasToken(name, token)) return true;
        return false;
    }

    private String classifyAutoBlock(Material material) {
        String name = material.name();

        if (name.contains("GLASS")) return "Glass";
        if (name.endsWith("_CARPET") || name.equals("MOSS_CARPET")) return "Carpets";
        if (name.endsWith("_WOOL")) return "Wool";
        if (name.endsWith("_CONCRETE_POWDER")) return "Concrete Powder";
        if (name.endsWith("_CONCRETE")) return "Concrete";
        if (name.endsWith("_GLAZED_TERRACOTTA")) return "Glazed Terracotta";
        if (name.endsWith("_TERRACOTTA") || name.equals("TERRACOTTA")) return "Terracotta";

        if (name.endsWith("_LEAVES") || name.endsWith("_SAPLING") || name.equals("MANGROVE_PROPAGULE")
                || name.contains("AZALEA") || name.equals("COCOA") || name.equals("COCOA_BEANS")) return "Tree Products";
        if (hasAnyToken(name, FLOWER_TOKENS)) return "Flowers";
        if (name.contains("MUSHROOM") || name.endsWith("_FUNGUS") || name.contains("MUSHROOM_BLOCK")) return "Mushrooms";
        if (hasAnyToken(name, PLANT_TOKENS)) return "Plants";

        if (name.startsWith("NETHER_") || name.contains("NETHERRACK") || name.contains("SOUL_")
                || name.contains("BASALT") || name.contains("BLACKSTONE") || name.contains("NYLIUM")
                || name.contains("CRIMSON") || name.contains("WARPED") || name.contains("MAGMA")) return "Nether";
        if (name.startsWith("END_") || name.contains("PURPUR") || name.contains("CHORUS") || name.equals("DRAGON_EGG")) return "End";

        if (name.endsWith("_ORE") || (name.endsWith("_BLOCK") && ORE_METAL_PREFIXES.contains(name.substring(0, name.length() - 6)))) return "Ores & Minerals";
        if (name.endsWith("_DOOR") || name.endsWith("_TRAPDOOR")) return "Doors & Trapdoors";
        if (name.endsWith("_FENCE") || name.endsWith("_FENCE_GATE") || name.endsWith("_WALL") || name.equals("IRON_BARS")) return "Fences & Walls";
        if (hasAnyToken(name, STORAGE_TOKENS)) return "Storage";
        if (hasAnyToken(name, WORKSTATION_TOKENS)) return "Workstations";
        if (hasAnyToken(name, LIGHTING_TOKENS)) return "Lighting";
        if (hasAnyToken(name, REDSTONE_TOKENS)) return "Redstone";

        if (name.contains("BRICK") || name.contains("PRISMARINE") || name.contains("QUARTZ")
                || name.contains("TILE") || name.contains("CHISELED")) return "Bricks & Masonry";
        if (WOOD_SPECIES.stream().anyMatch(species -> hasToken(name, species)) || name.contains("PLANKS")
                || name.contains("LOG") || name.contains("WOOD") || name.contains("STEM") || name.contains("HYPHAE")
                || name.contains("SIGN") || name.equals("LADDER") || name.equals("SCAFFOLDING")) return "Wood";
        if (name.contains("STONE") || name.contains("COBBLE") || name.contains("DEEPSLATE") || name.contains("TUFF")
                || name.contains("GRANITE") || name.contains("DIORITE") || name.contains("ANDESITE")
                || name.contains("CALCITE") || name.contains("DRIPSTONE")) return "Stone";
        if (hasAnyToken(name, TERRAIN_TOKENS)) return "Terrain";
        if (name.contains("CORAL") || name.contains("POT") || name.contains("BANNER") || name.contains("BED")
                || name.contains("SKULL") || name.contains("HEAD") || name.equals("CHAIN") || name.equals("PAINTING")
                || name.contains("ITEM_FRAME") || name.equals("ARMOR_STAND") || name.equals("JUKEBOX")) return "Decoration";

        return "Miscellaneous";
    }

    /**
     * Gives every auto-catalog item a stable, individual dollars-and-cents price.
     * The category tier supplies the economic scale; crafting shape, processing,
     * rarity signals, and a tiny deterministic material offset stop hundreds of
     * unrelated blocks from sharing one identical price.
     */
    private double[] varyAutoPrice(Material material, String category, double baseBuy, double baseSell) {
        String name = material.name();
        double effort = 1.0;

        if (name.endsWith("_SLAB")) effort *= 0.58;
        else if (name.endsWith("_STAIRS")) effort *= 0.86;
        else if (name.endsWith("_WALL")) effort *= 0.72;
        else if (name.endsWith("_FENCE")) effort *= 0.78;
        else if (name.endsWith("_FENCE_GATE")) effort *= 1.18;
        else if (name.endsWith("_DOOR")) effort *= 1.35;
        else if (name.endsWith("_TRAPDOOR")) effort *= 1.12;
        else if (name.endsWith("_PANE")) effort *= 0.46;
        else if (name.endsWith("_CARPET")) effort *= 0.42;

        if (name.contains("POLISHED") || name.contains("CUT_") || name.contains("CHISELED")) effort *= 1.18;
        if (name.contains("BRICK") || name.contains("TILE")) effort *= 1.28;
        if (name.contains("WAXED")) effort *= 1.22;
        if (name.contains("OXIDIZED") || name.contains("WEATHERED")) effort *= 1.30;
        if (name.contains("GLAZED")) effort *= 1.42;
        if (name.contains("INFESTED")) effort *= 1.55;
        if (name.contains("SCULK") || name.contains("AMETHYST")) effort *= 1.70;
        if (category.equals("Tree Products") || category.equals("Plants") || category.equals("Flowers")) effort *= 0.62;
        if (category.equals("Nether")) effort *= 1.30;
        if (category.equals("End")) effort *= 1.65;

        int hash = Math.floorMod(name.hashCode(), 1000);
        double individual = 0.91 + (hash / 1000.0) * 0.18; // stable 0.91 .. 1.08982
        double buy = Math.max(0.01, round2(baseBuy * effort * individual + (hash % 97) / 100.0));

        // Selling remains intentionally far below buying. Harder categories get
        // a slightly better return, rewarding gathering without enabling flips.
        double returnFactor = switch (category) {
            case "Ores & Minerals", "Nether", "End" -> 1.08;
            case "Redstone", "Workstations", "Storage" -> 1.03;
            case "Plants", "Flowers", "Tree Products", "Terrain" -> 0.92;
            default -> 1.0;
        };
        double sell = Math.max(0.01, round2(baseSell * effort * individual * returnFactor + (hash % 43) / 100.0));
        if (sell >= buy) sell = round2(Math.max(0.01, buy * 0.22));
        return new double[]{buy, sell};
    }

    private static final Set<String> ORE_METAL_PREFIXES = Set.of(
            "COAL", "IRON", "GOLD", "DIAMOND", "EMERALD", "LAPIS", "COPPER", "NETHERITE",
            "QUARTZ", "AMETHYST", "RAW_IRON", "RAW_GOLD", "RAW_COPPER");

    private Material autoCategoryIcon(String bucketName) {
        return switch (bucketName) {
            case "Glass" -> Material.GLASS;
            case "Carpets" -> Material.RED_CARPET;
            case "Wool" -> Material.WHITE_WOOL;
            case "Concrete" -> Material.WHITE_CONCRETE;
            case "Concrete Powder" -> Material.WHITE_CONCRETE_POWDER;
            case "Terracotta" -> Material.TERRACOTTA;
            case "Glazed Terracotta" -> Material.WHITE_GLAZED_TERRACOTTA;
            case "Tree Products" -> Material.OAK_SAPLING;
            case "Flowers" -> Material.POPPY;
            case "Plants" -> Material.FERN;
            case "Mushrooms" -> Material.RED_MUSHROOM;
            case "Wood" -> Material.OAK_PLANKS;
            case "Doors & Trapdoors" -> Material.OAK_DOOR;
            case "Fences & Walls" -> Material.OAK_FENCE;
            case "Stone" -> Material.STONE_BRICKS;
            case "Bricks & Masonry" -> Material.BRICKS;
            case "Ores & Minerals" -> Material.DIAMOND_ORE;
            case "Redstone" -> Material.REDSTONE;
            case "Lighting" -> Material.LANTERN;
            case "Storage" -> Material.CHEST;
            case "Workstations" -> Material.CRAFTING_TABLE;
            case "Decoration" -> Material.PAINTING;
            case "Nether" -> Material.NETHERRACK;
            case "End" -> Material.END_STONE;
            case "Terrain" -> Material.GRASS_BLOCK;
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