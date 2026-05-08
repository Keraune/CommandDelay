package dev.keraune.commanddelay.placeholder;

import dev.keraune.commanddelay.CommandDelay;
import dev.keraune.commanddelay.config.MessageService;
import dev.keraune.commanddelay.config.PluginSettings;
import dev.keraune.commanddelay.model.NextActionSnapshot;
import dev.keraune.commanddelay.model.ScheduleDefinition;
import dev.keraune.commanddelay.util.TextFormatter;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private static final DateTimeFormatter STATIC_TIME_24_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter STATIC_TIME_24_AMPM_FORMAT = DateTimeFormatter.ofPattern("HH:mm a", Locale.US);
    private static final DateTimeFormatter STATIC_TIME_12_FORMAT = DateTimeFormatter.ofPattern("h:mm a", Locale.US);

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
            case "next_schedule_display", "next_schedule_display_name", "next_schedule_name", "next_display_name", "next_name" -> globalScheduleName("legacy");
            case "next_schedule_raw_name", "next_schedule_name_raw", "next_raw_name" -> globalScheduleName("raw");
            case "next_schedule_plain_name", "next_schedule_name_plain", "next_plain_name" -> globalScheduleName("plain");
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
            case "next_schedule_time", "next_schedule_time_24", "next_static_time", "next_static_time_24" -> globalScheduleTime("time_24");
            case "next_schedule_time_24_ampm", "next_static_time_24_ampm" -> globalScheduleTime("time_24_ampm");
            case "next_schedule_time_12", "next_static_time_12" -> globalScheduleTime("time_12");
            case "next_schedule_times", "next_schedule_times_24", "next_static_times", "next_static_times_24" -> globalScheduleTime("times_24");
            case "next_schedule_times_24_ampm", "next_static_times_24_ampm" -> globalScheduleTime("times_24_ampm");
            case "next_schedule_times_12", "next_static_times_12" -> globalScheduleTime("times_12");
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
     * %commanddelay_name_boss_dragon%
     */
    private Optional<ScheduleSpecificPlaceholder> parseLegacySchedulePlaceholder(String params) {
        String[][] prefixes = {
                {"times_24_ampm_", "times_24_ampm"},
                {"time_24_ampm_", "time_24_ampm"},
                {"times_12_", "times_12"},
                {"time_12_", "time_12"},
                {"times_24_", "times_24"},
                {"time_24_", "time_24"},
                {"times_", "times_24"},
                {"time_", "time_24"},
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
                {"status_", "status"},
                {"display_name_", "display_name"},
                {"name_raw_", "name_raw"},
                {"raw_name_", "name_raw"},
                {"name_plain_", "name_plain"},
                {"plain_name_", "name_plain"},
                {"name_", "display_name"}
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
     * %commanddelay_boss_dragon_name%
     */
    private Optional<ScheduleSpecificPlaceholder> parseModernSchedulePlaceholder(String params) {
        return plugin.scheduleRepository().all().stream()
                .flatMap(schedule -> lookupKeys(schedule).stream().map(key -> new ScheduleLookup(schedule, key)))
                .sorted(Comparator.comparingInt((ScheduleLookup lookup) -> lookup.key().length()).reversed())
                .filter(lookup -> params.equals(lookup.key()) || params.startsWith(lookup.key() + "_"))
                .findFirst()
                .flatMap(lookup -> {
                    ScheduleDefinition schedule = lookup.schedule();
                    String key = lookup.key();

                    if (params.equals(key)) {
                        return Optional.of(new ScheduleSpecificPlaceholder(schedule.id(), "remaining_short"));
                    }

                    String suffix = params.substring(key.length() + 1);
                    String type = normalizeType(suffix);
                    return type.isBlank()
                            ? Optional.empty()
                            : Optional.of(new ScheduleSpecificPlaceholder(schedule.id(), type));
                });
    }

    private String resolveScheduleValue(ScheduleSpecificPlaceholder placeholder) {
        Optional<ScheduleDefinition> optionalSchedule = findSchedule(placeholder.scheduleId());

        if (optionalSchedule.isEmpty()) {
            return unavailable();
        }

        ScheduleDefinition schedule = optionalSchedule.get();

        if (placeholder.type().equals("id")) {
            return schedule.id();
        }

        if (isNameType(placeholder.type())) {
            return scheduleName(schedule, placeholder.type());
        }

        if (isStaticTimeType(placeholder.type())) {
            return scheduleTime(schedule, placeholder.type());
        }

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

    private String globalScheduleName(String format) {
        return globalSnapshot()
                .flatMap(snapshot -> findSchedule(snapshot.scheduleId()))
                .map(schedule -> switch (format) {
                    case "raw" -> schedule.displayName();
                    case "plain" -> TextFormatter.plain(schedule.displayName());
                    default -> TextFormatter.legacy(schedule.displayName());
                })
                .orElse(unavailable());
    }

    private String globalScheduleTime(String type) {
        return globalSnapshot()
                .flatMap(snapshot -> findSchedule(snapshot.scheduleId()))
                .map(schedule -> scheduleTime(schedule, type))
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
            case "time", "time_24", "schedule_time", "schedule_time_24", "static_time", "static_time_24" -> "time_24";
            case "time_24_ampm", "schedule_time_24_ampm", "static_time_24_ampm" -> "time_24_ampm";
            case "time_12", "time_ampm", "schedule_time_12", "static_time_12" -> "time_12";
            case "times", "times_24", "schedule_times", "schedule_times_24", "static_times", "static_times_24" -> "times_24";
            case "times_24_ampm", "schedule_times_24_ampm", "static_times_24_ampm" -> "times_24_ampm";
            case "times_12", "times_ampm", "schedule_times_12", "static_times_12" -> "times_12";
            case "next_action" -> "next_action";
            case "next_time" -> "next_time";
            case "next_date" -> "next_date";
            case "next_datetime" -> "next_datetime";
            case "status" -> "status";
            case "id", "schedule" -> "id";
            case "name", "display", "display_name", "schedule_name", "schedule_display", "schedule_display_name" -> "display_name";
            case "name_raw", "raw_name", "display_name_raw", "display_raw" -> "name_raw";
            case "name_plain", "plain_name", "display_name_plain", "display_plain" -> "name_plain";
            default -> "";
        };
    }

    private boolean isNameType(String type) {
        return type.equals("display_name") || type.equals("name_raw") || type.equals("name_plain");
    }

    private String scheduleName(ScheduleDefinition schedule, String type) {
        return switch (type) {
            case "name_raw" -> schedule.displayName();
            case "name_plain" -> TextFormatter.plain(schedule.displayName());
            default -> TextFormatter.legacy(schedule.displayName());
        };
    }

    private boolean isStaticTimeType(String type) {
        return type.equals("time_24")
                || type.equals("time_24_ampm")
                || type.equals("time_12")
                || type.equals("times_24")
                || type.equals("times_24_ampm")
                || type.equals("times_12");
    }

    private String scheduleTime(ScheduleDefinition schedule, String type) {
        List<LocalTime> times = schedule.times();

        if (times.isEmpty()) {
            return unavailable();
        }

        boolean multiple = type.startsWith("times_");

        if (!multiple) {
            return formatStaticTime(times.getFirst(), type);
        }

        List<String> formattedTimes = times.stream()
                .map(time -> formatStaticTime(time, type))
                .toList();
        return String.join(", ", formattedTimes);
    }

    private String formatStaticTime(LocalTime time, String type) {
        return switch (type) {
            case "time_12", "times_12" -> time.format(STATIC_TIME_12_FORMAT);
            case "time_24_ampm", "times_24_ampm" -> time.format(STATIC_TIME_24_AMPM_FORMAT);
            default -> time.format(STATIC_TIME_24_FORMAT);
        };
    }

    private Optional<ScheduleDefinition> findSchedule(String scheduleId) {
        Optional<ScheduleDefinition> exact = plugin.scheduleRepository().find(scheduleId);

        if (exact.isPresent()) {
            return exact;
        }

        Set<String> inputKeys = lookupVariants(scheduleId);

        return plugin.scheduleRepository().all().stream()
                .filter(schedule -> lookupKeys(schedule).stream().anyMatch(inputKeys::contains))
                .findFirst();
    }

    private Set<String> lookupKeys(ScheduleDefinition schedule) {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(lookupVariants(schedule.id()));
        keys.addAll(lookupVariants(schedule.displayName()));
        return keys;
    }

    /**
     * Builds tolerant keys for PlaceholderAPI lookups.
     *
     * <p>This intentionally accepts common server-owner variants such as
     * {@code Boss_Dragon}, {@code boss-dragon}, {@code dragon} and a colored
     * display name like {@code <#ff0000>&lDragon}.</p>
     */
    private Set<String> lookupVariants(String value) {
        Set<String> keys = new LinkedHashSet<>();

        if (value == null || value.isBlank()) {
            return keys;
        }

        addLookupKey(keys, normalize(value).replace('-', '_').replace(' ', '_'));
        addLookupKey(keys, placeholderKey(value));

        List<String> snapshot = List.copyOf(keys);
        for (String key : snapshot) {
            addLookupKey(keys, compactKey(key));

            if (key.startsWith("boss_")) {
                addLookupKey(keys, key.substring("boss_".length()));
                addLookupKey(keys, compactKey(key.substring("boss_".length())));
            } else if (!key.isBlank()) {
                addLookupKey(keys, "boss_" + key);
                addLookupKey(keys, "boss" + compactKey(key));
            }
        }

        return keys;
    }

    private void addLookupKey(Set<String> keys, String key) {
        if (key != null && !key.isBlank()) {
            keys.add(key.toLowerCase(Locale.ROOT));
        }
    }

    private String compactKey(String text) {
        return placeholderKey(text).replace("_", "");
    }

    /**
     * Converts a colored or spaced text into a PlaceholderAPI-safe lookup key.
     *
     * <p>Example: {@code "&#FF3300&lDragón Infernal"} -> {@code dragon_infernal}.</p>
     */
    private String placeholderKey(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String plain = TextFormatter.plain(text);
        String withoutAccents = Normalizer.normalize(plain, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return withoutAccents
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }

    private ZonedDateTime now() {
        PluginSettings settings = plugin.scheduleRunner().settings();
        return settings == null ? ZonedDateTime.now() : ZonedDateTime.now(settings.zoneId());
    }

    private String normalize(String params) {
        if (params == null) {
            return "";
        }

        return params.trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_')
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
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

    private record ScheduleLookup(ScheduleDefinition schedule, String key) {
    }

    private record CachedValue(String value, long createdAt) {
    }
}
