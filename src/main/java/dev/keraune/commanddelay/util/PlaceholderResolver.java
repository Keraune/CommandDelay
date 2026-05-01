package dev.keraune.commanddelay.util;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Resuelve placeholders internos y, si PlaceholderAPI está instalado, también
 * placeholders externos dentro de los textos configurables.
 *
 * <p>Se usa reflexión para que PlaceholderAPI siga siendo una dependencia opcional.</p>
 */
public final class PlaceholderResolver {

    private static final String PLACEHOLDER_API_PLUGIN = "PlaceholderAPI";
    private static Method setPlaceholdersMethod;
    private static boolean reflectionChecked;

    private PlaceholderResolver() {
    }

    public static String resolve(
            Plugin plugin,
            Player player,
            String input,
            boolean placeholderApiEnabled,
            Map<String, String> placeholders
    ) {
        return resolve(plugin, (OfflinePlayer) player, input, placeholderApiEnabled, placeholders);
    }

    public static String resolve(
            Plugin plugin,
            OfflinePlayer player,
            String input,
            boolean placeholderApiEnabled,
            Map<String, String> placeholders
    ) {
        if (input == null) {
            return "";
        }

        String result = applyInternalPlaceholders(input, placeholders);

        if (!placeholderApiEnabled || !isPlaceholderApiAvailable(plugin)) {
            return result;
        }

        try {
            Method method = placeholderApiMethod();
            if (method == null) {
                return result;
            }

            // PlaceholderAPI acepta OfflinePlayer. Cuando player es null, algunos placeholders
            // globales pueden resolverse igual; los placeholders por jugador se resuelven
            // cuando ScheduleRunner ejecuta la acción por cada jugador objetivo.
            Object parsed = method.invoke(null, player, result);
            return parsed instanceof String parsedText ? parsedText : result;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return result;
        }
    }

    public static String applyInternalPlaceholders(String input, Map<String, String> placeholders) {
        if (input == null) {
            return "";
        }

        String result = input;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
        }
        return result;
    }

    public static boolean isPlaceholderApiAvailable(Plugin plugin) {
        return Bukkit.getPluginManager().isPluginEnabled(PLACEHOLDER_API_PLUGIN)
                || plugin.getServer().getPluginManager().getPlugin(PLACEHOLDER_API_PLUGIN) != null;
    }

    private static Method placeholderApiMethod() throws ReflectiveOperationException {
        if (reflectionChecked) {
            return setPlaceholdersMethod;
        }

        reflectionChecked = true;
        Class<?> placeholderApiClass = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
        setPlaceholdersMethod = placeholderApiClass.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
        return setPlaceholdersMethod;
    }
}
