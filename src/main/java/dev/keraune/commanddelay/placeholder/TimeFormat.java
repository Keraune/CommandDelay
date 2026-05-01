package dev.keraune.commanddelay.placeholder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Formateador de tiempos para placeholders.
 *
 * <p>No lee configuración ni accede a Bukkit. Esto permite que los placeholders
 * sean rápidos y seguros para plugins que los consultan muchas veces por segundo.</p>
 */
public final class TimeFormat {

    private TimeFormat() {
    }

    public static String format(Duration duration, String format) {
        long seconds = Math.max(0L, duration.toSeconds());
        String normalizedFormat = format == null ? "default" : format.toLowerCase(Locale.ROOT);

        return switch (normalizedFormat) {
            case "long" -> longFormat(seconds);
            case "short", "default", "remaining" -> shortFormat(seconds);
            case "compact" -> compactFormat(seconds);
            case "clock", "digital" -> clockFormat(seconds);
            case "clock_full", "digital_full" -> clockFullFormat(seconds);
            case "seconds", "sec" -> String.valueOf(seconds);
            case "minutes", "min" -> String.valueOf(seconds / 60L);
            case "hours", "hour" -> String.valueOf(seconds / 3600L);
            default -> shortFormat(seconds);
        };
    }

    private static String longFormat(long totalSeconds) {
        long days = totalSeconds / 86_400L;
        long hours = (totalSeconds % 86_400L) / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        long seconds = totalSeconds % 60L;

        List<String> parts = new ArrayList<>();
        addLongPart(parts, days, "día", "días");
        addLongPart(parts, hours, "hora", "horas");
        addLongPart(parts, minutes, "minuto", "minutos");
        addLongPart(parts, seconds, "segundo", "segundos");

        if (parts.isEmpty()) {
            return "0 segundos";
        }

        if (parts.size() == 1) {
            return parts.getFirst();
        }

        String last = parts.removeLast();
        return String.join(", ", parts) + " y " + last;
    }

    private static String shortFormat(long totalSeconds) {
        long days = totalSeconds / 86_400L;
        long hours = (totalSeconds % 86_400L) / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        long seconds = totalSeconds % 60L;

        List<String> parts = new ArrayList<>();

        if (days > 0) {
            parts.add(days + "d");
        }
        if (hours > 0) {
            parts.add(hours + "h");
        }
        if (minutes > 0) {
            parts.add(minutes + "m");
        }
        if (seconds > 0 || parts.isEmpty()) {
            parts.add(seconds + "s");
        }

        return String.join(" ", parts);
    }

    private static String compactFormat(long totalSeconds) {
        return shortFormat(totalSeconds).replace(" ", "");
    }

    /**
     * Formato digital adaptativo: MM:SS cuando no hay horas, HH:MM:SS cuando hay horas.
     */
    private static String clockFormat(long totalSeconds) {
        long hours = totalSeconds / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        long seconds = totalSeconds % 60L;

        if (hours > 0) {
            return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds);
        }

        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    /**
     * Formato digital completo: HH:MM:SS siempre, usando horas acumuladas si pasa de un día.
     */
    private static String clockFullFormat(long totalSeconds) {
        long hours = totalSeconds / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    private static void addLongPart(List<String> parts, long value, String singular, String plural) {
        if (value <= 0) {
            return;
        }

        parts.add(value + " " + (value == 1 ? singular : plural));
    }
}
