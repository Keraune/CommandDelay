package dev.keraune.commanddelay.config;

import dev.keraune.commanddelay.CommandDelay;
import dev.keraune.commanddelay.util.PlaceholderResolver;
import dev.keraune.commanddelay.util.TextFormatter;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Servicio de mensajes configurables.
 *
 * <p>Todos los textos visibles para usuarios y consola se leen desde messages.yml.
 * De esta forma el plugin evita mensajes hardcodeados y permite personalización completa.</p>
 */
public final class MessageService {

    private final CommandDelay plugin;
    private final File messagesFile;
    private FileConfiguration messages;

    public MessageService(CommandDelay plugin) {
        this.plugin = plugin;
        this.messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        reload();
    }

    public void reload() {
        this.messages = YamlConfiguration.loadConfiguration(messagesFile);
    }

    public void send(CommandSender sender, String path, String... placeholders) {
        sender.sendMessage(component(sender, path, placeholders));
    }

    public void sendList(CommandSender sender, String path, String... placeholders) {
        for (String line : rawList(path, placeholders)) {
            sender.sendMessage(component(sender, line, Map.of()));
        }
    }

    public Component component(CommandSender sender, String path, String... placeholders) {
        return component(sender, raw(path, placeholders), Map.of());
    }

    public String legacy(String path, String... placeholders) {
        return TextFormatter.legacy(raw(path, placeholders));
    }

    public List<String> legacyList(String path, String... placeholders) {
        List<String> formatted = new ArrayList<>();
        for (String line : rawList(path, placeholders)) {
            formatted.add(TextFormatter.legacy(line));
        }
        return formatted;
    }

    public String plain(String path, String... placeholders) {
        return TextFormatter.plain(raw(path, placeholders));
    }

    private Component component(CommandSender sender, String rawText, Map<String, String> extraPlaceholders) {
        String withPrefix = applyPrefix(rawText);
        String withInternal = PlaceholderResolver.applyInternalPlaceholders(withPrefix, extraPlaceholders);

        if (sender instanceof Player player) {
            boolean papiEnabled = plugin.getConfig().getBoolean("settings.placeholderapi.enabled", true);
            withInternal = PlaceholderResolver.resolve(plugin, player, withInternal, papiEnabled, Map.of());
        }

        return TextFormatter.component(withInternal);
    }

    private String raw(String path, String... placeholders) {
        String value = messages.getString(path, path);
        return applyPlaceholders(value, placeholders);
    }

    private List<String> rawList(String path, String... placeholders) {
        List<String> rawLines = messages.getStringList(path);
        List<String> formatted = new ArrayList<>();

        for (String line : rawLines) {
            formatted.add(applyPlaceholders(line, placeholders));
        }

        return formatted;
    }

    private String applyPlaceholders(String value, String... placeholders) {
        String result = applyPrefix(value);
        Map<String, String> map = new HashMap<>();

        for (int index = 0; index + 1 < placeholders.length; index += 2) {
            map.put(placeholders[index], placeholders[index + 1]);
        }

        return PlaceholderResolver.applyInternalPlaceholders(result, map);
    }

    private String applyPrefix(String value) {
        return value.replace("%prefix%", messages.getString("prefix", ""));
    }
}
