package dev.keraune.commanddelay.util;

/**
 * Compatibilidad con el antiguo formateador legacy del plugin.
 *
 * <p>Ahora delega al mismo sistema de colores usado en SpookyMenus, soportando
 * &, &#RRGGBB, <#RRGGBB>, #RRGGBB, tags simples y gradientes.</p>
 */
public final class LegacyText {

    private LegacyText() {
    }

    public static String colorize(String value) {
        return TextUtil.color(value);
    }
}
