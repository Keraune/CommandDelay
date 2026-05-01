package dev.keraune.commanddelay.config;

import dev.keraune.commanddelay.CommandDelay;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.ZoneId;
import java.time.zone.ZoneRulesException;

/**
 * Centraliza la lectura de config.yml.
 *
 * <p>Evita que el resto del código lea rutas YAML directamente, haciendo que
 * los cambios futuros de configuración sean más seguros y fáciles de mantener.</p>
 */
public final class ConfigManager {

    private final CommandDelay plugin;
    private final MessageService messageService;

    public ConfigManager(CommandDelay plugin, MessageService messageService) {
        this.plugin = plugin;
        this.messageService = messageService;
    }

    public void reload() {
        plugin.reloadConfig();
    }

    public FileConfiguration config() {
        return plugin.getConfig();
    }

    public PluginSettings loadSettings() {
        FileConfiguration config = config();

        String timezone = config.getString("settings.timezone", "America/Lima");
        ZoneId zoneId = loadZoneId(timezone);

        int checkIntervalSeconds = Math.max(1, config.getInt("settings.check-interval-seconds", 20));
        boolean removeLeadingSlash = config.getBoolean("settings.remove-leading-slash", true);
        boolean translateCommandColors = config.getBoolean("settings.translate-command-colors", true);
        boolean useMiniMessage = config.getBoolean("settings.use-minimessage", true);
        boolean placeholderApiEnabled = config.getBoolean("settings.placeholderapi.enabled", true);
        int historyRetentionDays = Math.max(1, config.getInt("settings.history-retention-days", 30));
        boolean debug = config.getBoolean("settings.debug", false);

        return new PluginSettings(
                zoneId,
                checkIntervalSeconds,
                removeLeadingSlash,
                translateCommandColors,
                useMiniMessage,
                placeholderApiEnabled,
                historyRetentionDays,
                debug
        );
    }

    private ZoneId loadZoneId(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (ZoneRulesException | NullPointerException exception) {
            plugin.getLogger().warning(messageService.plain(
                    "logs.invalid-timezone",
                    "%timezone%", String.valueOf(timezone)
            ));
            return ZoneId.of("America/Lima");
        }
    }
}
