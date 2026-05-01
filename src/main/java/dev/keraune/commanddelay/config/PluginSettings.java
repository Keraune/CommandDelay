package dev.keraune.commanddelay.config;

import java.time.ZoneId;

/**
 * Configuración técnica ya validada y lista para ser usada por los servicios.
 */
public record PluginSettings(
        ZoneId zoneId,
        int checkIntervalSeconds,
        boolean removeLeadingSlash,
        boolean translateCommandColors,
        boolean useMiniMessage,
        boolean placeholderApiEnabled,
        int historyRetentionDays,
        boolean debug
) {
}
