package dev.shopscraper;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses EconomyShopGUI lore. Handles both layouts:
 *   Buy Price            Buy Price: $360.00
 *   ■ $360.00
 * and "Sell Price" followed by "■ $90.00 $112.50" (struck-through old price, then the real one -> last number wins).
 * Pure Java so it can be tested without Minecraft.
 */
public final class PriceParser {

    /** Heading like "Buy Price", "Sell Price:", "Buy: $5". Rest must contain no words (so "Sell All..." / "Buy 64 items" don't match). */
    private static final Pattern HEADER = Pattern.compile(
            "(?i)^[^A-Za-z0-9]*(buy|sell)(?:\\s*price)?\\s*:?\\s*([^A-Za-z]*[kmb]?)\\s*$");

    private static final Pattern NUMBER = Pattern.compile(
            "(?i)([0-9][0-9.,]*)(?:\\s*([kmb])(?![a-z]))?");

    /** @return {buy, sell}; either may be null. */
    public static Double[] parse(List<String> lines) {
        Double buy = null, sell = null;
        String pending = null;
        for (String line : lines) {
            Matcher h = HEADER.matcher(line.trim());
            if (h.matches()) {
                String type = h.group(1).toLowerCase();
                Double v = lastNumber(h.group(2));
                if (v != null) {
                    if (type.equals("buy") && buy == null) buy = v;
                    if (type.equals("sell") && sell == null) sell = v;
                    pending = null;
                } else {
                    pending = type;
                }
                continue;
            }
            if (pending != null) {
                Double v = lastNumber(line);
                if (v != null) {
                    if (pending.equals("buy") && buy == null) buy = v;
                    if (pending.equals("sell") && sell == null) sell = v;
                }
                pending = null;
            }
        }
        return new Double[]{buy, sell};
    }

    private static Double lastNumber(String s) {
        Double last = null;
        Matcher m = NUMBER.matcher(s);
        while (m.find()) {
            Double v = parseNumber(m.group(1), m.group(2));
            if (v != null) last = v;
        }
        return last;
    }

    /** Handles "1,234.50", "1.234,50", "1234", "12,5", "1.5k". */
    public static Double parseNumber(String raw, String suffix) {
        String s = raw.replaceAll("[.,]+$", "");
        if (s.isEmpty()) return null;
        int lastDot = s.lastIndexOf('.');
        int lastComma = s.lastIndexOf(',');
        try {
            if (lastDot >= 0 && lastComma >= 0) {
                char dec = lastDot > lastComma ? '.' : ',';
                char thou = dec == '.' ? ',' : '.';
                s = s.replace(String.valueOf(thou), "").replace(dec, '.');
            } else if (lastComma >= 0) {
                boolean thousands = s.indexOf(',') != lastComma || s.length() - lastComma - 1 == 3;
                s = thousands ? s.replace(",", "") : s.replace(',', '.');
            } else if (lastDot >= 0 && s.indexOf('.') != lastDot) {
                s = s.replace(".", "");
            }
            double v = Double.parseDouble(s);
            if (suffix != null) {
                v *= switch (suffix.toLowerCase()) { case "k" -> 1e3; case "m" -> 1e6; default -> 1e9; };
            }
            return v;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
