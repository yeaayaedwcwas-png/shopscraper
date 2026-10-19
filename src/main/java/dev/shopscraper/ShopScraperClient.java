package dev.shopscraper;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Passive, client-side price collector. Open any EconomyShopGUI menu yourself;
 * while a container screen is open, items whose lore has "Buy: ..." / "Sell: ..."
 * lines are recorded. The CSV is written when you close the menu.
 */
public class ShopScraperClient implements ClientModInitializer {

    /** Only scan screens whose title matches this (case-insensitive). Use ".*" to scan every container. */
    private static final Pattern TITLE_FILTER = Pattern.compile(".*", Pattern.CASE_INSENSITIVE);

    /** 1 = scan every client tick (20x/second), the fastest the client can see new menu contents. */
    private static final int SCAN_INTERVAL_TICKS = 1;

    /** While a menu stays open, flush new prices to disk this often (ticks) so nothing is lost if the game crashes. */
    private static final int AUTOSAVE_TICKS = 100;
    private static int sinceSave = 0;

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    private static Path csvPath;
    private static boolean dirty = false;
    private static boolean wasScanning = false;
    private static int tickCounter = 0;
    private static int newThisSession = 0;

    record Entry(String itemId, String name, Double buy, Double sell) {}

    @Override
    public void onInitializeClient() {
        csvPath = FabricLoader.getInstance().getGameDir().resolve("shop_prices.csv");
        loadExisting();
        ClientTickEvents.END_CLIENT_TICK.register(ShopScraperClient::onTick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(ClientCommands.literal("shopflips")
                        .executes(ctx -> showFlips(ctx.getSource(), 10))
                        .then(ClientCommands.argument("count", IntegerArgumentType.integer(1, 50))
                                .executes(ctx -> showFlips(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "count"))))));
    }

    // ---------------------------------------------------------------- flip finder

    private static final FlipFinder FINDER = new FlipFinder();
    private static boolean recipesLoaded = false;

    private static void ensureRecipes(FabricClientCommandSource src) {
        if (recipesLoaded) return;
        recipesLoaded = true;
        try {
            ModContainer mc = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow();
            for (Path root : mc.getRootPaths()) {
                if (Files.isDirectory(root.resolve("data/minecraft/recipe"))) {
                    FINDER.loadRecipes(root);
                    break;
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        if (FINDER.recipeCount() == 0) {
            src.sendFeedback(Component.literal("[ShopScraper] Could not load vanilla recipes - only direct buy/sell flips will be shown."));
        }
    }

    private static int showFlips(FabricClientCommandSource src, int limit) {
        if (dirty) save(); // include prices from the menu that is still open
        if (ENTRIES.isEmpty()) {
            src.sendFeedback(Component.literal("[ShopScraper] No prices recorded yet. Open some shop menus first."));
            return 0;
        }
        ensureRecipes(src);

        Map<String, FlipFinder.Price> prices = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        for (Entry e : ENTRIES.values()) {
            FlipFinder.Price old = prices.get(e.itemId());
            Double buy = e.buy(), sell = e.sell();
            if (old != null) { // several entries for one item: lowest buy, highest sell
                if (old.buy() != null && (buy == null || old.buy() < buy)) buy = old.buy();
                if (old.sell() != null && (sell == null || old.sell() > sell)) sell = old.sell();
            }
            prices.put(e.itemId(), new FlipFinder.Price(buy, sell));
            names.putIfAbsent(e.itemId(), e.name());
        }

        List<FlipFinder.Flip> flips = FINDER.find(prices, names);
        writeFlipsCsv(flips);
        if (flips.isEmpty()) {
            src.sendFeedback(Component.literal("[ShopScraper] No profitable flips found with " + prices.size()
                    + " priced items. Scan more shop categories (crafting needs both ingredient buy prices and the result's sell price)."));
            return 0;
        }
        src.sendFeedback(Component.literal("[ShopScraper] Top " + Math.min(limit, flips.size()) + " of " + flips.size() + " flips:"));
        for (int i = 0; i < Math.min(limit, flips.size()); i++) {
            FlipFinder.Flip f = flips.get(i);
            src.sendFeedback(Component.literal(String.format("%d. [%s] %s: +%s (%.0f%%) - %s",
                    i + 1, f.kind(), f.item(), FlipFinder.fmt(f.profit()), f.roi(), f.detail())));
        }
        src.sendFeedback(Component.literal("Full list: shop_flips.csv"));
        return flips.size();
    }

    private static void writeFlipsCsv(List<FlipFinder.Flip> flips) {
        StringBuilder sb = new StringBuilder("kind,item,profit,roi_percent,cost,revenue,detail\n");
        for (FlipFinder.Flip f : flips) {
            sb.append(f.kind()).append(',').append(csv(f.item())).append(',')
              .append(fmt(f.profit())).append(',').append(String.format(java.util.Locale.ROOT, "%.1f", f.roi())).append(',')
              .append(fmt(f.cost())).append(',').append(fmt(f.revenue())).append(',').append(csv(f.detail())).append('\n');
        }
        try {
            Files.writeString(csvPath.resolveSibling("shop_flips.csv"), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    private static void onTick(Minecraft mc) {
        boolean scanning = mc.screen instanceof AbstractContainerScreen<?> screen
                && TITLE_FILTER.matcher(screen.getTitle().getString()).find();

        if (scanning) {
            if (++tickCounter >= SCAN_INTERVAL_TICKS) {
                tickCounter = 0;
                scan((AbstractContainerScreen<?>) mc.screen);
            }
        } else {
            tickCounter = 0;
        }

        // Periodic autosave while browsing (cheap: only writes when something changed).
        if (dirty && ++sinceSave >= AUTOSAVE_TICKS) {
            save();
            dirty = false;
            sinceSave = 0;
        }

        // Screen just closed / changed away from a shop screen -> flush to disk.
        if (wasScanning && !scanning && dirty) {
            save();
            dirty = false;
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal(
                        "[ShopScraper] Saved " + ENTRIES.size() + " items to shop_prices.csv (+" + newThisSession + " this session). Type /shopflips for flip ideas."));
            }
        }
        wasScanning = scanning;
    }

    private static void scan(AbstractContainerScreen<?> screen) {
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory) continue; // skip the player's own inventory
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;

            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore == null) continue;

            List<String> lines = new ArrayList<>();
            for (Component line : lore.lines()) lines.add(line.getString());
            Double[] prices = PriceParser.parse(lines);
            Double buy = prices[0], sell = prices[1];
            if (buy == null && sell == null) continue;

            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            String name = stack.getHoverName().getString();
            String key = id + "|" + name;
            Entry entry = new Entry(id, name, buy, sell);
            Entry old = ENTRIES.put(key, entry);
            if (old == null) newThisSession++;
            if (!entry.equals(old)) dirty = true;
        }
    }

    private static void save() {
        StringBuilder sb = new StringBuilder("item_id,name,buy,sell\n");
        for (Entry e : ENTRIES.values()) {
            sb.append(e.itemId()).append(',')
              .append(csv(e.name())).append(',')
              .append(e.buy() == null ? "" : fmt(e.buy())).append(',')
              .append(e.sell() == null ? "" : fmt(e.sell())).append('\n');
        }
        try {
            Files.writeString(csvPath, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    private static void loadExisting() {
        if (!Files.exists(csvPath)) return;
        try {
            List<String> lines = Files.readAllLines(csvPath, StandardCharsets.UTF_8);
            for (int i = 1; i < lines.size(); i++) {
                List<String> f = splitCsv(lines.get(i));
                if (f.size() < 4) continue;
                Double buy = f.get(2).isEmpty() ? null : Double.valueOf(f.get(2));
                Double sell = f.get(3).isEmpty() ? null : Double.valueOf(f.get(3));
                ENTRIES.put(f.get(0) + "|" + f.get(1), new Entry(f.get(0), f.get(1), buy, sell));
            }
        } catch (Exception ignored) { }
    }

    private static String fmt(double d) {
        return d == Math.floor(d) ? Long.toString((long) d) : Double.toString(d);
    }

    private static String csv(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (q) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else if (c == '"') q = false;
                else cur.append(c);
            } else if (c == '"') q = true;
            else if (c == ',') { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }
}
