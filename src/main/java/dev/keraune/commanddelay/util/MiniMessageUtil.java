package dev.keraune.commanddelay.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Puente centralizado entre MiniMessage y formatos legacy.
 *
 * <p>Responsabilidad técnica:</p>
 * <ul>
 *     <li>Permitir MiniMessage real: {@code <red>}, {@code <bold>}, gradientes, etc.</li>
 *     <li>Mantener compatibilidad con colores legacy: {@code &a}, {@code &l}, {@code &#RRGGBB}.</li>
 *     <li>Evitar que el normalizador rompa tags propios de MiniMessage como
 *     {@code <gradient:#FFAA00:#FF3300>}.</li>
 * </ul>
 */
public final class MiniMessageUtil {

    private static volatile Replacer replacer = new InactiveReplacer();

    private MiniMessageUtil() {
    }

    public static void init(boolean useMiniMessage) {
        replacer = useMiniMessage ? new ActiveReplacer() : new InactiveReplacer();
    }

    public static boolean isEnabled() {
        return replacer instanceof ActiveReplacer;
    }

    public static String parseToLegacy(String input) {
        return replacer.parseToLegacy(input);
    }

    public static List<String> parseToLegacy(List<String> input) {
        return replacer.parseToLegacy(input);
    }

    public static Component parseComponent(String input) {
        return replacer.parseComponent(input);
    }

    public static void sendParsed(String input, Player player) {
        replacer.sendParsed(input, player);
    }

    public static void sendParsed(List<String> input, Player player) {
        replacer.sendParsed(input, player);
    }

    private interface Replacer {
        String parseToLegacy(String input);

        List<String> parseToLegacy(List<String> input);

        Component parseComponent(String input);

        void sendParsed(String input, Player player);

        void sendParsed(List<String> input, Player player);
    }

    private static final class ActiveReplacer implements Replacer {

        private static final Pattern AMP_HEX = Pattern.compile("(?i)&x(?:&[0-9a-f]){6}");
        private static final Pattern SECTION_HEX = Pattern.compile("(?i)§x(?:§[0-9a-f]){6}");
        private static final Pattern LEGACY_HEX = Pattern.compile("(?i)[&§]#([0-9a-f]{6})");

        /**
         * Convierte tokens #RRGGBB sueltos a MiniMessage.
         *
         * <p>No debe coincidir con colores internos de tags como:
         * {@code <gradient:#FFAA00:#FF3300>}, porque eso convierte el tag en algo inválido
         * y el jugador termina viendo literalmente {@code <gradient:...>} en el chat.</p>
         */
        private static final Pattern PLAIN_HEX = Pattern.compile("(?<![A-Fa-f0-9])#([A-Fa-f0-9]{6})");

        private static final Pattern LEGACY_CODE = Pattern.compile("(?i)[&§]([0-9a-fk-or])");
        private static final Map<String, String> LEGACY_TAGS = Map.ofEntries(
                Map.entry("0", "<black>"),
                Map.entry("1", "<dark_blue>"),
                Map.entry("2", "<dark_green>"),
                Map.entry("3", "<dark_aqua>"),
                Map.entry("4", "<dark_red>"),
                Map.entry("5", "<dark_purple>"),
                Map.entry("6", "<gold>"),
                Map.entry("7", "<gray>"),
                Map.entry("8", "<dark_gray>"),
                Map.entry("9", "<blue>"),
                Map.entry("a", "<green>"),
                Map.entry("b", "<aqua>"),
                Map.entry("c", "<red>"),
                Map.entry("d", "<light_purple>"),
                Map.entry("e", "<yellow>"),
                Map.entry("f", "<white>"),
                Map.entry("k", "<obf>"),
                Map.entry("l", "<bold>"),
                Map.entry("m", "<strikethrough>"),
                Map.entry("n", "<underlined>"),
                Map.entry("o", "<italic>"),
                Map.entry("r", "<reset>")
        );

        private final MiniMessage mini = MiniMessage.miniMessage();
        private final LegacyComponentSerializer legacy = LegacyComponentSerializer.builder()
                .character(LegacyComponentSerializer.SECTION_CHAR)
                .hexColors()
                .useUnusualXRepeatedCharacterHexFormat()
                .build();

        @Override
        public String parseToLegacy(String input) {
            if (input == null) {
                return "";
            }

            if (input.isEmpty()) {
                return input;
            }

            try {
                return legacy.serialize(mini.deserialize(normalizeForMiniMessage(input)));
            } catch (RuntimeException exception) {
                return TextUtil.color(input);
            }
        }

        @Override
        public List<String> parseToLegacy(List<String> input) {
            if (input == null) {
                return List.of();
            }

            if (input.isEmpty()) {
                return input;
            }

            List<String> out = new ArrayList<>(input.size());
            for (String line : input) {
                out.add(parseToLegacy(line));
            }
            return out;
        }

        @Override
        public Component parseComponent(String input) {
            if (input == null || input.isEmpty()) {
                return Component.empty();
            }

            try {
                return mini.deserialize(normalizeForMiniMessage(input));
            } catch (RuntimeException exception) {
                return legacy.deserialize(TextUtil.color(input));
            }
        }

        @Override
        public void sendParsed(String input, Player player) {
            if (player == null || input == null) {
                return;
            }

            if (input.isEmpty()) {
                player.sendMessage("");
                return;
            }

            player.sendMessage(parseComponent(input));
        }

        @Override
        public void sendParsed(List<String> input, Player player) {
            if (input == null || player == null) {
                return;
            }

            for (String line : input) {
                sendParsed(line, player);
            }
        }

        private static String normalizeForMiniMessage(String input) {
            String out = input;
            out = replaceRepeatedLegacyHex(out, AMP_HEX, '&');
            out = replaceRepeatedLegacyHex(out, SECTION_HEX, '§');
            out = replaceSimpleLegacyHex(out);
            out = replacePlainHex(out);
            out = replaceLegacyCodes(out);
            return out;
        }

        private static String replaceRepeatedLegacyHex(String input, Pattern pattern, char prefix) {
            Matcher matcher = pattern.matcher(input);
            StringBuffer sb = new StringBuffer();

            while (matcher.find()) {
                String token = matcher.group();
                String hex = token.replace(String.valueOf(prefix), "").substring(1);
                matcher.appendReplacement(sb, Matcher.quoteReplacement("<#" + hex.toUpperCase(Locale.ROOT) + ">"));
            }

            matcher.appendTail(sb);
            return sb.toString();
        }

        private static String replaceSimpleLegacyHex(String input) {
            Matcher matcher = LEGACY_HEX.matcher(input);
            StringBuffer sb = new StringBuffer();

            while (matcher.find()) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement("<#" + matcher.group(1).toUpperCase(Locale.ROOT) + ">"));
            }

            matcher.appendTail(sb);
            return sb.toString();
        }

        private static String replacePlainHex(String input) {
            StringBuilder result = new StringBuilder(input.length());
            StringBuilder plainSegment = new StringBuilder();
            boolean insideMiniMessageTag = false;

            for (int index = 0; index < input.length(); index++) {
                char character = input.charAt(index);

                if (character == '<') {
                    appendPlainHexSegment(result, plainSegment);
                    insideMiniMessageTag = true;
                    result.append(character);
                    continue;
                }

                if (character == '>') {
                    insideMiniMessageTag = false;
                    result.append(character);
                    continue;
                }

                if (insideMiniMessageTag) {
                    result.append(character);
                    continue;
                }

                plainSegment.append(character);
            }

            appendPlainHexSegment(result, plainSegment);
            return result.toString();
        }

        private static void appendPlainHexSegment(StringBuilder result, StringBuilder segment) {
            if (segment.length() == 0) {
                return;
            }

            Matcher matcher = PLAIN_HEX.matcher(segment);
            StringBuffer sb = new StringBuffer();

            while (matcher.find()) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement("<#" + matcher.group(1).toUpperCase(Locale.ROOT) + ">"));
            }

            matcher.appendTail(sb);
            result.append(sb);
            segment.setLength(0);
        }

        private static String replaceLegacyCodes(String input) {
            Matcher matcher = LEGACY_CODE.matcher(input);
            StringBuffer sb = new StringBuffer();

            while (matcher.find()) {
                String replacement = LEGACY_TAGS.get(matcher.group(1).toLowerCase(Locale.ROOT));
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement != null ? replacement : matcher.group()));
            }

            matcher.appendTail(sb);
            return sb.toString();
        }
    }

    private static final class InactiveReplacer implements Replacer {

        private final LegacyComponentSerializer legacy = LegacyComponentSerializer.builder()
                .character(LegacyComponentSerializer.SECTION_CHAR)
                .hexColors()
                .useUnusualXRepeatedCharacterHexFormat()
                .build();

        @Override
        public String parseToLegacy(String input) {
            if (input == null) {
                return "";
            }

            return TextUtil.color(input);
        }

        @Override
        public List<String> parseToLegacy(List<String> input) {
            if (input == null) {
                return List.of();
            }

            if (input.isEmpty()) {
                return input;
            }

            List<String> out = new ArrayList<>(input.size());
            for (String line : input) {
                out.add(parseToLegacy(line));
            }
            return out;
        }

        @Override
        public Component parseComponent(String input) {
            if (input == null || input.isEmpty()) {
                return Component.empty();
            }

            return legacy.deserialize(TextUtil.color(input));
        }

        @Override
        public void sendParsed(String input, Player player) {
            if (player == null || input == null) {
                return;
            }

            player.sendMessage(parseToLegacy(input));
        }

        @Override
        public void sendParsed(List<String> input, Player player) {
            if (input == null || player == null) {
                return;
            }

            for (String line : input) {
                sendParsed(line, player);
            }
        }
    }
}
