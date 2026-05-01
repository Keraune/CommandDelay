package dev.keraune.commanddelay.util;

import net.kyori.adventure.text.Component;

import java.util.List;

/**
 * Puente único para convertir textos configurables a Component o legacy.
 *
 * <p>Este plugin acepta varios formatos para que la configuración sea cómoda:</p>
 * <ul>
 *     <li>Legacy: &a, &c, &l.</li>
 *     <li>Hex: &#FFAA00, <#FFAA00>, #FFAA00.</li>
 *     <li>MiniMessage: &lt;red&gt;, &lt;bold&gt;, &lt;gradient:#FF0000:#FFFF00&gt;Texto&lt;/gradient&gt;.</li>
 * </ul>
 */
public final class TextFormatter {

    private TextFormatter() {
    }

    public static void init(boolean useMiniMessage) {
        MiniMessageUtil.init(useMiniMessage);
    }

    public static Component component(String input) {
        return MiniMessageUtil.parseComponent(input);
    }

    public static String legacy(String input) {
        return MiniMessageUtil.parseToLegacy(input);
    }

    public static List<String> legacyList(List<String> input) {
        return MiniMessageUtil.parseToLegacy(input);
    }

    public static String plain(String input) {
        return TextUtil.stripColor(legacy(input));
    }
}
