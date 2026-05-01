package dev.keraune.commanddelay.util;

import org.bukkit.ChatColor;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rich text formatter:
 * - Legacy '&' colors
 * - Hex formats: &#RRGGBB, <#RRGGBB>, &x&F&F&F&F&F&F
 * - Simple style tags: <b>, <i>, <u>, <st>, <obf>, <reset>, and color names like <red>
 * - Gradients: <gradient:#FF0000:#00FF00>Text</gradient>
 */
public final class TextUtil {

    private TextUtil() {}

    private static final Pattern GRADIENT_PATTERN =
            Pattern.compile("<gradient:((?:#[A-Fa-f0-9]{6}:?)+)>(.*?)</gradient>", Pattern.DOTALL);

    private static final Pattern LEGACY_HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final Pattern TAG_HEX_PATTERN = Pattern.compile("<#([A-Fa-f0-9]{6})>");
    private static final Pattern TAG_HEX_CLOSE_PATTERN = Pattern.compile("</#([A-Fa-f0-9]{6})>");
    // Support plain "#RRGGBB" tokens, even when they appear after punctuation such as ":"
    // in titles like "Tienda:#9E09C8&l Armas". Gradients are expanded before this runs.
    private static final Pattern PLAIN_HEX_PATTERN = Pattern.compile("(?<![A-Fa-f0-9])#([A-Fa-f0-9]{6})");
    private static final Pattern STYLE_PATTERN = Pattern.compile("<(/?\\w+)>");
    private static final Pattern STRIP_COLOR_PATTERN = Pattern.compile("(?i)§[0-9A-FK-ORX]|&[0-9A-FK-ORX]|<[^>]*>");
    private static final Pattern BASE64_URL = Pattern.compile("\"url\"\\s*:\\s*\"(https?://[^\"]+)\"");

    public static String color(String input) {
        if (input == null || input.isEmpty()) return "";

        String text = input;

        // 0) Remove closing hex tags like </#00FFAA>
        text = TAG_HEX_CLOSE_PATTERN.matcher(text).replaceAll("");

        text = convertLegacyXFormat(text);
        text = applyStyleTags(text);
        text = applyGradientsFinal(text);
        text = processTagHex(text);
        text = processLegacyHex(text);

        // Support plain #RRGGBB (after gradients / tag-hex are processed)
        text = processPlainHex(text);

        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private static String processPlainHex(String text) {
        Matcher matcher = PLAIN_HEX_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(sb, toHex(matcher.group(1)));
        return matcher.appendTail(sb).toString();
    }

    private static String convertLegacyXFormat(String text) {
        Pattern pattern = Pattern.compile("(&x)((&[0-9A-Fa-f]){6})");
        Matcher matcher = pattern.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(2).replaceAll("&", "");
            matcher.appendReplacement(sb, "&#" + hex.toUpperCase(Locale.ROOT));
        }
        matcher.appendTail(sb);

        String result = sb.toString();
        pattern = Pattern.compile("(§x)((§[0-9A-Fa-f]){6})");
        matcher = pattern.matcher(result);
        sb = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(2).replaceAll("§", "");
            matcher.appendReplacement(sb, "&#" + hex.toUpperCase(Locale.ROOT));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String applyStyleTags(String text) {
        Matcher matcher = STYLE_PATTERN.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String tag = matcher.group(1).toLowerCase(Locale.ROOT);
            String replacement = switch (tag) {
                case "b", "bold" -> "§l";
                case "i", "italic" -> "§o";
                case "u", "underline" -> "§n";
                case "st", "strikethrough" -> "§m";
                case "obf", "obfuscated", "magic" -> "§k";
                case "/b", "/bold", "/i", "/italic", "/u", "/underline", "/st", "/strikethrough", "/obf", "/obfuscated", "reset", "/reset" -> "§r";
                default -> {
                    if (tag.startsWith("/")) {
                        String closingColor = getColorFromName(tag.substring(1));
                        yield (closingColor != null) ? "§r" : matcher.group(0);
                    }

                    String color = getColorFromName(tag);
                    yield (color != null) ? color : matcher.group(0);
                }
            };
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String applyGradientsFinal(String text) {
        Matcher matcher = GRADIENT_PATTERN.matcher(text);
        StringBuffer buffer = new StringBuffer();

        while (matcher.find()) {
            String colorList = matcher.group(1);
            String content = matcher.group(2);

            String activeFormats = getActiveFormats(text.substring(0, matcher.start()));
            String internalFormats = getInternalFormats(content);
            String combinedFormats = activeFormats + internalFormats;

            // Do not trim here. MiniMessage gradients can intentionally start/end with spaces,
            // for example: <gradient:#A:#B> Poción</gradient>. Trimming caused words from
            // adjacent gradient segments to be joined together.
            String cleanText = stripFormatting(content, false);
            String gradient = buildFinalGradient(cleanText, colorList, combinedFormats);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(gradient));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String getActiveFormats(String textBefore) {
        StringBuilder formats = new StringBuilder();
        for (int i = textBefore.length() - 1; i >= 0; i--) {
            if (textBefore.charAt(i) == '§' && i + 1 < textBefore.length()) {
                char code = textBefore.charAt(i + 1);
                if ("0123456789abcdefr".indexOf(code) != -1) break;
                if ("lomnk".indexOf(code) != -1) {
                    String f = "§" + code;
                    if (formats.indexOf(f) == -1) formats.insert(0, f);
                }
            }
        }
        return formats.toString();
    }

    private static String getInternalFormats(String content) {
        StringBuilder formats = new StringBuilder();
        Set<Character> activeFormats = new HashSet<>();

        for (int i = 0; i < content.length(); i++) {
            char current = content.charAt(i);

            if ((current == '§' || current == '&') && i + 1 < content.length()) {
                char code = content.charAt(i + 1);

                if ("lomnk".indexOf(code) >= 0) {
                    activeFormats.add(code);
                } else if (code == 'r') {
                    activeFormats.clear();
                } else if ("0123456789abcdef".indexOf(code) >= 0) {
                    activeFormats.clear();
                }
                i++;
            } else {
                if (!activeFormats.isEmpty()) {
                    StringBuilder currentFormats = new StringBuilder();
                    for (char formatCode : activeFormats) {
                        currentFormats.append("§").append(formatCode);
                    }
                    if (!currentFormats.toString().equals(formats.toString())) {
                        formats.setLength(0);
                        formats.append(currentFormats);
                    }
                }
            }
        }

        return formats.toString();
    }

    private static String buildFinalGradient(String text, String colorList, String formats) {
        String[] hexList = colorList.split(":");
        if (hexList.length == 0 || text.isEmpty()) return formats + text;

        List<int[]> colors = new ArrayList<>();
        for (String hex : hexList) {
            try {
                int c = Integer.parseInt(hex.replace("#", ""), 16);
                colors.add(new int[]{(c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF});
            } catch (Exception ignored) {}
        }

        if (colors.isEmpty()) return formats + text;

        // Java chars can split UTF-16 surrogate pairs. Minecraft symbols/emojis such as 🗡, 🛡, 🧪
        // must be colored as one Unicode code point, otherwise a color sequence can be inserted
        // between the high and low surrogate and the client renders replacement/error symbols.
        int[] codePoints = text.codePoints().toArray();
        int len = codePoints.length;
        StringBuilder out = new StringBuilder(text.length() * 16);
        for (int i = 0; i < len; i++) {
            double progress = (len > 1) ? (double) i / (len - 1) * (colors.size() - 1) : 0;
            int index = (int) progress;
            double ratio = progress - index;

            int[] start = colors.get(index);
            int[] end = colors.get(Math.min(index + 1, colors.size() - 1));

            int r = (int) (start[0] + (end[0] - start[0]) * ratio);
            int g = (int) (start[1] + (end[1] - start[1]) * ratio);
            int b = (int) (start[2] + (end[2] - start[2]) * ratio);

            out.append(toHex(String.format(Locale.ROOT, "%02X%02X%02X", r, g, b)))
                    .append(formats)
                    .appendCodePoint(codePoints[i]);
        }
        return out.toString();
    }

    private static String processTagHex(String text) {
        Matcher matcher = TAG_HEX_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(sb, toHex(matcher.group(1)));
        return matcher.appendTail(sb).toString();
    }

    private static String processLegacyHex(String text) {
        Matcher matcher = LEGACY_HEX_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(sb, toHex(matcher.group(1)));
        return matcher.appendTail(sb).toString();
    }

    private static String toHex(String hex) {
        StringBuilder result = new StringBuilder("§x");
        for (char c : hex.toCharArray()) result.append("§").append(c);
        return result.toString();
    }

    private static String getColorFromName(String name) {
        return switch (name) {
            case "black" -> "§0";
            case "dark_blue" -> "§1";
            case "dark_green" -> "§2";
            case "dark_aqua" -> "§3";
            case "dark_red" -> "§4";
            case "dark_purple" -> "§5";
            case "gold" -> "§6";
            case "gray" -> "§7";
            case "dark_gray" -> "§8";
            case "blue" -> "§9";
            case "green" -> "§a";
            case "aqua" -> "§b";
            case "red" -> "§c";
            case "light_purple" -> "§d";
            case "yellow" -> "§e";
            case "white" -> "§f";
            default -> null;
        };
    }

    public static String stripColor(String input) {
        return stripFormatting(input, true);
    }

    private static String stripFormatting(String input, boolean trim) {
        if (input == null) return "";
        String stripped = STRIP_COLOR_PATTERN.matcher(input).replaceAll("");
        stripped = stripped.replaceAll("<gradient:[^>]*>|</gradient>", "");
        return trim ? stripped.trim() : stripped;
    }
}
