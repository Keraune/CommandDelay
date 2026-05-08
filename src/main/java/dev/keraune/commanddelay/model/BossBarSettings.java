package dev.keraune.commanddelay.model;

import java.util.List;

/**
 * Immutable configuration for a scheduled boss bar action.
 *
 * <p>The scheduler resolves placeholders per player at execution time, so this
 * model only stores validated YAML values and never depends on Bukkit state.</p>
 */
public record BossBarSettings(
        String message,
        String color,
        String overlay,
        float progress,
        int durationTicks,
        boolean decreaseProgress,
        List<String> flags
) {
    public BossBarSettings {
        flags = flags == null ? List.of() : List.copyOf(flags);
    }
}
