package dev.keraune.commanddelay.placeholder;

import dev.keraune.commanddelay.CommandDelay;
import dev.keraune.commanddelay.config.MessageService;
import dev.keraune.commanddelay.config.PluginSettings;
import dev.keraune.commanddelay.model.NextActionSnapshot;
import dev.keraune.commanddelay.model.ScheduleDefinition;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Expansión interna de PlaceholderAPI para CommandDelay.
 *
 * <p>Permite que plugins externos compatibles con PlaceholderAPI, como hologramas,
 * scoreboards, TAB o menús, consulten el estado de los horarios y el tiempo restante
 * hasta la siguiente acción.</p>
 *
 * <p>La expansión no lee archivos YAML en cada consulta. Todo se calcula con los horarios
 * ya cargados en memoria por {@link dev.keraune.commanddelay.scheduler.ScheduleRepository}
 * y con el estado activo del {@link dev.keraune.commanddelay.scheduler.ScheduleRunner}.</p>
 */
public final class CommandDelayExpansion extends PlaceholderExpansion {

    private static final long CACHE_MILLIS = 250L;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CommandDelay plugin;

    private final Map<String, CachedValue> cache = new ConcurrentHashMap<>();

    public CommandDelayExpansion(CommandDelay plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "commanddelay";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        String normalizedParams = normalize(params);
        long now = System.currentTimeMillis();

        CachedValue cached = cache.get(normalizedParams);

        if (cached != null && now - cached.createdAt() <= CACHE_MILLIS) {
            return cached.value();
        }

        String value = resolve(normalizedParams);

        if (cache.size() > 256) {
            cache.clear();
        }

        cache.put(normalizedParams, new CachedValue(value, now));
        return value;
    }

    /**
     * Limpia el cache de placeholders. Se llama después de reload para evitar respuestas antiguas.
     */
    public void clearCache() {
        cache.clear();
    }

    private String resolve(String params) {
        if (params.isBlank()) {
            return unavailable();
        }

        return switch (params) {
            case "next_schedule" -> globalSnapshot().map(NextActionSnapshot::scheduleId).orElse(unavailable());
            case "next_action" -> globalSnapshot().map(snapshot -> snapshot.actionType().name()).orElse(unavailable());
            case "next_status" -> globalStatus();
            case "next_remaining", "next_remaining_short" -> globalRemaining("short");
            case "next_remaining_long" -> globalRemaining("long");
            case "next_remaining_compact" -> globalRemaining("compact");
            case "next_remaining_clock", "next_remaining_digital" -> globalRemaining("clock");
            case "next_remaining_clock_full", "next_remaining_digital_full" -> globalRemaining("clock_full");
            case "next_remaining_seconds" -> globalRemaining("seconds");
            case "next_remaining_minutes" -> globalRemaining("minutes");
            case "next_remaining_hours" -> globalRemaining("hours");
            case "next_time" -> globalSnapshot().map(snapshot -> snapshot.dueAt().format(TIME_FORMAT)).orElse(unavailable());
            case "next_date" -> globalSnapshot().map(snapshot -> snapshot.dueAt().format(DATE_FORMAT)).orElse(unavailable());
            case "next_datetime" -> globalSnapshot().map(snapshot -> snapshot.dueAt().format(DATE_TIME_FORMAT)).orElse(unavailable());
            case "total_schedules" -> String.valueOf(plugin.scheduleRepository().size());
            default -> resolveSchedulePlaceholder(params);
        };
    }

    private String resolveSchedulePlaceholder(String params) {
        Optional<ScheduleSpecificPlaceholder> legacy = parseLegacySchedulePlaceholder(params);

        if (legacy.isPresent()) {
            return resolveScheduleValue(legacy.get());
        }

        Optional<ScheduleSpecificPlaceholder> modern = parseModernSchedulePlaceholder(params);

        if (modern.isPresent()) {
            return resolveScheduleValue(modern.get());
        }

        return unavailable();
    }

    /**
     * Soporta placeholders con formato:
     * %commanddelay_remaining_boss_dragon%
     * %commanddelay_next_action_boss_dragon%
     * %commanddelay_status_boss_dragon%
     */
    private Optional<ScheduleSpecificPlaceholder> parseLegacySchedulePlaceholder(String params) {
        String[][] prefixes = {
                {"remaining_seconds_", "remaining_seconds"},
                {"remaining_minutes_", "remaining_minutes"},
                {"remaining_hours_", "remaining_hours"},
                {"remaining_clock_full_", "remaining_clock_full"},
                {"remaining_digital_full_", "remaining_clock_full"},
                {"remaining_clock_", "remaining_clock"},
                {"remaining_digital_", "remaining_clock"},
                {"remaining_compact_", "remaining_compact"},
                {"remaining_long_", "remaining_long"},
                {"remaining_short_", "remaining_short"},
                {"remaining_", "remaining_short"},
                {"next_action_", "next_action"},
                {"next_time_", "next_time"},
                {"next_date_", "next_date"},
                {"next_datetime_", "next_datetime"},
                {"status_", "status"}
        };

        for (String[] entry : prefixes) {
            String prefix = entry[0];
            String type = entry[1];

            if (!params.startsWith(prefix)) {
                continue;
            }

            String scheduleId = params.substring(prefix.length());

            Optional<ScheduleDefinition> schedule = findSchedule(scheduleId);

            if (schedule.isPresent()) {
                return Optional.of(new ScheduleSpecificPlaceholder(schedule.get().id(), type));
            }
        }

        return Optional.empty();
    }

    /**
     * Soporta placeholders con formato:
     * %commanddelay_boss_dragon_remaining%
     * %commanddelay_boss_dragon_remaining_clock%
     * %commanddelay_boss_dragon_next_action%
     */
    private Optional<ScheduleSpecificPlaceholder> parseModernSchedulePlaceholder(String params) {
        return plugin.scheduleRepository().all().stream()
                .map(ScheduleDefinition::id)
                .sorted(Comparator.comparingInt(String::length).reversed())
                .filter(scheduleId -> {
                    String normalizedId = scheduleId.toLowerCase(Locale.ROOT);
                    return params.equals(normalizedId) || params.startsWith(normalizedId + "_");
                })
                .findFirst()
                .flatMap(scheduleId -> {
                    String normalizedId = scheduleId.toLowerCase(Locale.ROOT);

                    if (params.equals(normalizedId)) {
                        return Optional.of(new ScheduleSpecificPlaceholder(scheduleId, "remaining_short"));
                    }

                    String suffix = params.substring(normalizedId.length() + 1);
                    String type = normalizeType(suffix);
                    return type.isBlank()
                            ? Optional.empty()
                            : Optional.of(new ScheduleSpecificPlaceholder(scheduleId, type));
                });
    }

    private String resolveScheduleValue(ScheduleSpecificPlaceholder placeholder) {
        Optional<ScheduleDefinition> optionalSchedule = findSchedule(placeholder.scheduleId());

        if (optionalSchedule.isEmpty()) {
            return unavailable();
        }

        ScheduleDefinition schedule = optionalSchedule.get();

        if (!schedule.enabled()) {
            return placeholder.type().equals("status") ? status("disabled") : unavailable();
        }

        Optional<NextActionSnapshot> optionalSnapshot = plugin.scheduleRunner().nextAction(schedule.id());

        if (placeholder.type().equals("status")) {
            return scheduleStatus(schedule, optionalSnapshot);
        }

        if (optionalSnapshot.isEmpty()) {
            return unavailable();
        }

        NextActionSnapshot snapshot = optionalSnapshot.get();

        return switch (placeholder.type()) {
            case "remaining_short" -> remaining(snapshot, "short");
            case "remaining_long" -> remaining(snapshot, "long");
            case "remaining_compact" -> remaining(snapshot, "compact");
            case "remaining_clock" -> remaining(snapshot, "clock");
            case "remaining_clock_full" -> remaining(snapshot, "clock_full");
            case "remaining_seconds" -> remaining(snapshot, "seconds");
            case "remaining_minutes" -> remaining(snapshot, "minutes");
            case "remaining_hours" -> remaining(snapshot, "hours");
            case "next_action" -> snapshot.actionType().name();
            case "next_time" -> snapshot.dueAt().format(TIME_FORMAT);
            case "next_date" -> snapshot.dueAt().format(DATE_FORMAT);
            case "next_datetime" -> snapshot.dueAt().format(DATE_TIME_FORMAT);
            default -> unavailable();
        };
    }

    private Optional<NextActionSnapshot> globalSnapshot() {
        return plugin.scheduleRunner().nextAction();
    }

    private String globalRemaining(String format) {
        return globalSnapshot()
                .map(snapshot -> remaining(snapshot, format))
                .orElse(unavailable());
    }

    private String globalStatus() {
        return globalSnapshot()
                .map(snapshot -> snapshot.active() ? status("running") : status("waiting"))
                .orElse(status("none"));
    }

    private String scheduleStatus(ScheduleDefinition schedule, Optional<NextActionSnapshot> optionalSnapshot) {
        if (!schedule.enabled()) {
            return status("disabled");
        }

        if (optionalSnapshot.isEmpty()) {
            return status("none");
        }

        return optionalSnapshot.get().active() ? status("running") : status("waiting");
    }

    private String remaining(NextActionSnapshot snapshot, String format) {
        ZonedDateTime now = now();
        Duration remaining = snapshot.remaining(now);
        return TimeFormat.format(remaining, format);
    }

    private String normalizeType(String rawType) {
        return switch (rawType) {
            case "remaining", "remaining_short" -> "remaining_short";
            case "remaining_long" -> "remaining_long";
            case "remaining_compact" -> "remaining_compact";
            case "remaining_clock", "remaining_digital" -> "remaining_clock";
            case "remaining_clock_full", "remaining_digital_full" -> "remaining_clock_full";
            case "remaining_seconds" -> "remaining_seconds";
            case "remaining_minutes" -> "remaining_minutes";
            case "remaining_hours" -> "remaining_hours";
            case "next_action" -> "next_action";
            case "next_time" -> "next_time";
            case "next_date" -> "next_date";
            case "next_datetime" -> "next_datetime";
            case "status" -> "status";
            default -> "";
        };
    }



    private Optional<ScheduleDefinition> findSchedule(String scheduleId) {
        Optional<ScheduleDefinition> exact = plugin.scheduleRepository().find(scheduleId);

        if (exact.isPresent()) {
            return exact;
        }

        return plugin.scheduleRepository().all().stream()
                .filter(schedule -> schedule.id().equalsIgnoreCase(scheduleId))
                .findFirst();
    }

    private ZonedDateTime now() {
        PluginSettings settings = plugin.scheduleRunner().settings();
        return settings == null ? ZonedDateTime.now() : ZonedDateTime.now(settings.zoneId());
    }

    private String normalize(String params) {
        return params == null ? "" : params.trim().toLowerCase(Locale.ROOT);
    }

    private String unavailable() {
        return message("placeholders.unavailable", "N/A");
    }

    private String status(String status) {
        return message("placeholders.status." + status, status);
    }

    private String message(String path, String fallback) {
        MessageService messages = plugin.messageService();

        if (messages == null) {
            return fallback;
        }

        String value = messages.legacy(path);
        return value == null || value.isBlank() || value.equals(path) ? fallback : value;
    }

    private record ScheduleSpecificPlaceholder(String scheduleId, String type) {
    }

    private record CachedValue(String value, long createdAt) {
    }
}
