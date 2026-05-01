package dev.keraune.commanddelay.model;

import java.util.Locale;

/**
 * Tipos de acciones soportadas por un horario.
 */
public enum ActionType {
    COMMAND,
    CHAT,
    TITLE,
    ACTIONBAR,
    SOUND;

    public static ActionType from(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return COMMAND;
        }

        String normalized = rawType.trim().toUpperCase(Locale.ROOT).replace('-', '_');

        return switch (normalized) {
            case "COMMAND", "CMD", "CONSOLE" -> COMMAND;
            case "CHAT", "BROADCAST", "MESSAGE", "GLOBAL_MESSAGE", "GLOBAL" -> CHAT;
            case "TITLE" -> TITLE;
            case "ACTIONBAR", "ACTION_BAR" -> ACTIONBAR;
            case "SOUND", "PLAY_SOUND" -> SOUND;
            default -> COMMAND;
        };
    }
}
