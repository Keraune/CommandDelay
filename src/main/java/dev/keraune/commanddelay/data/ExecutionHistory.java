package dev.keraune.commanddelay.data;

import dev.keraune.commanddelay.CommandDelay;
import dev.keraune.commanddelay.config.MessageService;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Historial interno de ejecuciones.
 *
 * <p>Su función principal es evitar duplicados. Como el scheduler revisa cada ciertos
 * segundos, un horario de las 08:00 podría ser detectado más de una vez durante ese minuto.
 * Este archivo registra qué horario ya se ejecutó en una fecha y hora determinada.</p>
 */
public final class ExecutionHistory {

    private static final DateTimeFormatter TIME_KEY_FORMATTER = DateTimeFormatter.ofPattern("HH_mm");

    private final CommandDelay plugin;
    private final MessageService messageService;
    private final File historyFile;
    private FileConfiguration history;

    public ExecutionHistory(CommandDelay plugin, MessageService messageService) {
        this.plugin = plugin;
        this.messageService = messageService;
        this.historyFile = new File(plugin.getDataFolder(), "last-executions.yml");
        createFileIfMissing();
        reload();
    }

    public void reload() {
        try {
            this.history = YamlConfiguration.loadConfiguration(historyFile);
        } catch (Exception exception) {
            plugin.getLogger().warning(messageService.plain("logs.history-load-error"));
            this.history = new YamlConfiguration();
        }
    }

    public boolean hasExecuted(String scheduleId, LocalDate date, LocalTime time) {
        return history.getBoolean(path(scheduleId, date, time), false);
    }

    public void markExecuted(String scheduleId, LocalDate date, LocalTime time) {
        history.set(path(scheduleId, date, time), true);
        save();
    }

    public void prune(int retentionDays, ZoneId zoneId) {
        ConfigurationSection executions = history.getConfigurationSection("executions");

        if (executions == null) {
            return;
        }

        LocalDate limit = LocalDate.now(zoneId).minusDays(retentionDays);
        boolean changed = false;

        for (String dateKey : executions.getKeys(false)) {
            try {
                LocalDate date = LocalDate.parse(dateKey);

                if (date.isBefore(limit)) {
                    history.set("executions." + dateKey, null);
                    changed = true;
                }
            } catch (DateTimeParseException ignored) {
                history.set("executions." + dateKey, null);
                changed = true;
            }
        }

        if (changed) {
            save();
        }
    }

    public void save() {
        try {
            history.save(historyFile);
        } catch (IOException exception) {
            plugin.getLogger().warning(messageService.plain("logs.history-save-error"));
        }
    }

    private void createFileIfMissing() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        if (historyFile.exists()) {
            return;
        }

        try {
            if (historyFile.createNewFile()) {
                plugin.getLogger().info(messageService.plain(
                        "logs.file-created",
                        "%file%", historyFile.getName()
                ));
            }
        } catch (IOException exception) {
            plugin.getLogger().warning(messageService.plain(
                    "logs.file-create-error",
                    "%file%", historyFile.getName()
            ));
        }
    }

    private String path(String scheduleId, LocalDate date, LocalTime time) {
        return "executions."
                + date
                + "."
                + sanitize(scheduleId)
                + "."
                + time.format(TIME_KEY_FORMATTER);
    }

    private String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
