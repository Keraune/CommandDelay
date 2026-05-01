package dev.keraune.commanddelay.model;

import org.bukkit.entity.Player;

import java.util.Set;

/**
 * Requisitos opcionales que deben cumplirse para ejecutar un horario.
 */
public record ScheduleRequirements(
        int minPlayersOnline,
        Set<String> worlds,
        String permission
) {

    public static ScheduleRequirements empty() {
        return new ScheduleRequirements(0, Set.of(), "");
    }

    public boolean matches(Player player) {
        if (player == null) {
            return false;
        }

        if (!worlds.isEmpty() && !worlds.contains(player.getWorld().getName())) {
            return false;
        }

        return permission == null || permission.isBlank() || player.hasPermission(permission);
    }
}
