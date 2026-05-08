package dev.keraune.commanddelay.model;

import java.util.Locale;

/**
 * Defines how action delay values are interpreted within a schedule execution.
 */
public enum ActionDelayMode {
    /**
     * Backward-compatible mode. Every action delay is calculated from the schedule start time.
     */
    ABSOLUTE,

    /**
     * Executes actions one by one. Each delay is calculated from the previous action execution.
     */
    SEQUENTIAL;

    public static ActionDelayMode from(String rawMode) {
        if (rawMode == null || rawMode.isBlank()) {
            return ABSOLUTE;
        }

        String normalized = rawMode.trim()
                .toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');

        return switch (normalized) {
            case "SEQUENTIAL", "CHAIN", "CHAINED", "RELATIVE", "ONE_BY_ONE", "ONEBYONE" -> SEQUENTIAL;
            case "ABSOLUTE", "PARALLEL", "FROM_START", "START" -> ABSOLUTE;
            default -> ABSOLUTE;
        };
    }
}
