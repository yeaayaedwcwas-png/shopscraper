package dev.shopscraper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Pure logic (no Minecraft classes): loads vanilla crafting recipes from a data root and
 * finds profitable flips using scraped shop prices.
 *
 * Price semantics (EconomyShopGUI): buy = what YOU pay, sell = what you RECEIVE.
 */
public final class FlipFinder {

    public record Price(Double buy, Double sell) {}

    /** One crafting recipe. Each ingredient slot is a set of acceptable item ids. */
    public record Recipe(String resultId, int resultCount, List<List<String>> ingredients) {}

    public record Flip(String kind, String item, String detail, double cost, double revenue) {
        public double profit() { return revenue - cost; }
        public double roi() { return cost <= 0 ? 0 : (revenue - cost) / cost * 100.0; }
    }

    private final List<Recipe> recipes = new ArrayList<>();
    private final Map<String, List<String>> tags = new HashMap<>();

    /** @param dataRoot the jar root containing data/minecraft/... */
    public void loadRecipes(Path dataRoot) throws IOException {
        recipes.clear();
        tags.clear();
        Path tagDir = dataRoot.resolve("data/minecraft/tags/item");
        if (Files.isDirectory(tagDir)) {
            try (Stream<Path> s = Files.walk(tagDir)) {
                for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".json"))::iterator) {
                    String rel = tagDir.relativize(p).toString().replace('\\', '/');
                    String name = "minecraft:" + rel.substring(0, rel.length() - 5);
                    JsonObject o = read(p);
                    List<String> values = new ArrayList<>();
                    if (o != null && o.has("values")) {
                        for (JsonElement e : o.getAsJsonArray("values")) {
                            if (e.isJsonPrimitive()) values.add(e.getAsString());
                            else if (e.isJsonObject() && e.getAsJsonObject().has("id")) values.add(e.getAsJsonObject().get("id").getAsString());
                        }
                    }
                    tags.put(name, values);
                }
            }
        }
        Path recipeDir = dataRoot.resolve("data/minecraft/recipe");
        if (!Files.isDirectory(recipeDir)) return;
        try (Stream<Path> s = Files.walk(recipeDir)) {
            for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".json"))::iterator) {
                try {
                    JsonObject o = read(p);
                    if (o != null) parseRecipe(o);
                } catch (RuntimeException ignored) { /* skip odd recipe */ }
            }
        }
    }

    public int recipeCount() { return recipes.size(); }

    private static JsonObject read(Path p) throws IOException {
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            JsonElement e = JsonParser.parseReader(r);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        }
    }

    private void parseRecipe(JsonObject o) {
        String type = o.has("type") ? o.get("type").getAsString() : "";
        if (!o.has("result")) return;
        JsonElement res = o.get("result");
        String resultId;
        int count = 1;
        if (res.isJsonObject()) {
            JsonObject r = res.getAsJsonObject();
            resultId = (r.has("id") ? r.get("id") : r.get("item")).getAsString();
            if (r.has("count")) count = r.get("count").getAsInt();
        } else {
            resultId = res.getAsString();
        }

        List<List<String>> slots = new ArrayList<>();
        if (type.endsWith("crafting_shaped")) {
            JsonObject key = o.getAsJsonObject("key");
            for (JsonElement row : o.getAsJsonArray("pattern")) {
                for (char c : row.getAsString().toCharArray()) {
                    if (c == ' ') continue;
                    JsonElement ing = key.get(String.valueOf(c));
                    if (ing == null) return;
                    slots.add(expand(ing));
                }
            }
        } else if (type.endsWith("crafting_shapeless")) {
            for (JsonElement ing : o.getAsJsonArray("ingredients")) slots.add(expand(ing));
        } else {
            return; // smelting, smithing, etc. not handled
        }
        if (!slots.isEmpty()) recipes.add(new Recipe(resultId, count, slots));
    }

    /** Ingredient = "id", "#tag", or an array of those. */
    private List<String> expand(JsonElement e) {
        List<String> out = new ArrayList<>();
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) out.addAll(expand(x));
        } else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("item")) out.add(o.get("item").getAsString());
            else if (o.has("tag")) out.addAll(expandTag(o.get("tag").getAsString(), 0));
        } else {
            String s = e.getAsString();
            if (s.startsWith("#")) out.addAll(expandTag(s.substring(1), 0));
            else out.add(s);
        }
        return out;
    }

    private List<String> expandTag(String tag, int depth) {
        if (depth > 5) return List.of();
        List<String> out = new ArrayList<>();
        for (String v : tags.getOrDefault(tag, List.of())) {
            if (v.startsWith("#")) out.addAll(expandTag(v.substring(1), depth + 1));
            else out.add(v);
        }
        return out;
    }

    /**
     * @param prices item id -> best known price (lowest buy, highest sell)
     * @param names  item id -> display name (optional)
     */
    public List<Flip> find(Map<String, Price> prices, Map<String, String> names) {
        List<Flip> flips = new ArrayList<>();

        // 1) Direct arbitrage: the shop pays more for an item than it charges.
        for (var e : prices.entrySet()) {
            Price p = e.getValue();
            if (p.buy() != null && p.sell() != null && p.sell() > p.buy()) {
                flips.add(new Flip("BUY->SELL", label(e.getKey(), names), "buy " + fmt(p.buy()) + " / sell " + fmt(p.sell()), p.buy(), p.sell()));
            }
        }

        // 2) Crafting: cost of ingredients (cheapest priced option per slot) vs sell price of the result.
        for (Recipe r : recipes) {
            Price out = prices.get(r.resultId());
            if (out == null || out.sell() == null) continue;
            double cost = 0;
            boolean ok = true;
            Map<String, Integer> used = new LinkedHashMap<>();
            for (List<String> options : r.ingredients()) {
                String best = null;
                double bestPrice = Double.MAX_VALUE;
                for (String opt : options) {
                    Price ip = prices.get(opt);
                    if (ip != null && ip.buy() != null && ip.buy() < bestPrice) { bestPrice = ip.buy(); best = opt; }
                }
                if (best == null) { ok = false; break; }
                cost += bestPrice;
                used.merge(short_(best), 1, Integer::sum);
            }
            if (!ok) continue;
            double revenue = out.sell() * r.resultCount();
            if (revenue > cost) {
                StringBuilder d = new StringBuilder("craft x" + r.resultCount() + " from ");
                used.forEach((k, v) -> d.append(v).append(' ').append(k).append(", "));
                d.setLength(d.length() - 2);
                flips.add(new Flip("CRAFT", label(r.resultId(), names), d.toString(), cost, revenue));
            }
        }
        flips.sort(Comparator.comparingDouble(Flip::profit).reversed());
        return flips;
    }

    private static String label(String id, Map<String, String> names) {
        String n = names == null ? null : names.get(id);
        return n != null ? n : short_(id);
    }

    private static String short_(String id) { return id.startsWith("minecraft:") ? id.substring(10) : id; }

    public static String fmt(double d) {
        return d == Math.floor(d) ? String.format(Locale.ROOT, "%,.0f", d) : String.format(Locale.ROOT, "%,.2f", d);
    }
}
