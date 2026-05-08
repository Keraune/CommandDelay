package dev.keraune.commanddelay.scheduler;

import dev.keraune.commanddelay.CommandDelay;
import dev.keraune.commanddelay.config.MessageService;
import dev.keraune.commanddelay.model.ActionDelayMode;
import dev.keraune.commanddelay.model.ActionType;
import dev.keraune.commanddelay.model.BossBarSettings;
import dev.keraune.commanddelay.model.ScheduleDefinition;
import dev.keraune.commanddelay.model.ScheduleRequirements;
import dev.keraune.commanddelay.model.ScheduledAction;
import dev.keraune.commanddelay.util.TextFormatter;
import org.bukkit.configuration.ConfigurationSection;

import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Carga y mantiene en memoria los horarios definidos en config.yml.
 *
 * <p>El repositorio valida la configuración una vez en cada reload. Así, el scheduler
 * trabaja con objetos limpios y no accede al YAML en cada revisión.</p>
 */
public final class ScheduleRepository {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final Pattern FLEXIBLE_TIME_PATTERN = Pattern.compile("^(\\d{1,2}):(\\d{2})(?:\\s*(AM|PM))?$", Pattern.CASE_INSENSITIVE);

    private final CommandDelay plugin;
    private final MessageService messageService;
    private final Map<String, ScheduleDefinition> schedules = new HashMap<>();

    /** Lista cacheada y ordenada para evitar ordenar en cada revisión del scheduler. */
    private List<ScheduleDefinition> cachedSchedules = List.of();

    public ScheduleRepository(CommandDelay plugin, MessageService messageService) {
        this.plugin = plugin;
        this.messageService = messageService;
    }

    public void reload() {
        schedules.clear();

        ConfigurationSection schedulesSection = plugin.getConfig().getConfigurationSection("schedules");

        if (schedulesSection == null) {
            cachedSchedules = List.of();
            plugin.getLogger().info(messageService.plain("logs.schedules-loaded", "%total%", "0"));
            return;
        }

        for (String scheduleId : schedulesSection.getKeys(false)) {
            ConfigurationSection section = schedulesSection.getConfigurationSection(scheduleId);

            if (section == null) {
                plugin.getLogger().warning(messageService.plain(
                        "logs.invalid-schedule-section",
                        "%schedule%", scheduleId
                ));
                continue;
            }

            loadSchedule(scheduleId, section).ifPresent(schedule -> schedules.put(schedule.id(), schedule));
        }

        cachedSchedules = schedules.values().stream()
                .sorted(java.util.Comparator.comparing(ScheduleDefinition::id))
                .toList();

        plugin.getLogger().info(messageService.plain(
                "logs.schedules-loaded",
                "%total%", String.valueOf(schedules.size())
        ));
    }

    public Optional<ScheduleDefinition> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }

        Optional<ScheduleDefinition> exact = Optional.ofNullable(schedules.get(id));

        if (exact.isPresent()) {
            return exact;
        }

        Set<String> inputKeys = lookupVariants(id);

        return cachedSchedules.stream()
                .filter(schedule -> lookupVariants(schedule.id()).stream().anyMatch(inputKeys::contains)
                        || lookupVariants(schedule.displayName()).stream().anyMatch(inputKeys::contains))
                .findFirst();
    }

    public Collection<ScheduleDefinition> all() {
        return cachedSchedules;
    }

    public int size() {
        return schedules.size();
    }

    private Optional<ScheduleDefinition> loadSchedule(String scheduleId, ConfigurationSection section) {
        boolean enabled = section.getBoolean("enabled", true);

        DayConfig dayConfig = parseDays(section.getStringList("days"));
        List<LocalTime> times = parseTimes(scheduleId, section.getStringList("times"));
        String displayName = parseDisplayName(scheduleId, section);
        ActionDelayMode actionDelayMode = parseActionDelayMode(section);
        ScheduleRequirements requirements = parseRequirements(section.getConfigurationSection("requirements"));
        List<ScheduledAction> actions = parseConfiguredActions(scheduleId, section);

        if (times.isEmpty()) {
            plugin.getLogger().warning(messageService.plain(
                    "logs.schedule-without-times",
                    "%schedule%", scheduleId
            ));
            return Optional.empty();
        }

        if (actions.isEmpty()) {
            plugin.getLogger().warning(messageService.plain(
                    "logs.schedule-without-actions",
                    "%schedule%", scheduleId
            ));
            return Optional.empty();
        }

        return Optional.of(new ScheduleDefinition(
                scheduleId,
                displayName,
                enabled,
                dayConfig.everyDay(),
                dayConfig.days(),
                times,
                actions,
                actionDelayMode,
                requirements,
                dayConfig.displayDays(),
                times.stream().map(TIME_FORMATTER::format).toList()
        ));
    }


    /**
     * Reads how delay-seconds should be interpreted for this schedule.
     *
     * <p>ABSOLUTE keeps the legacy behavior: every delay is counted from the
     * schedule start. SEQUENTIAL executes actions one by one: every delay is
     * counted from the previous action execution.</p>
     */
    private ActionDelayMode parseActionDelayMode(ConfigurationSection section) {
        String rawMode = section.getString("action-delay-mode");

        if (rawMode == null || rawMode.isBlank()) {
            rawMode = section.getString("delay-mode");
        }

        if (rawMode == null || rawMode.isBlank()) {
            rawMode = section.getString("execution-mode");
        }

        if (rawMode == null || rawMode.isBlank()) {
            rawMode = section.getString("actions-mode");
        }

        return ActionDelayMode.from(rawMode);
    }

    /**
     * Lee el nombre visual del horario.
     *
     * <p>Se aceptan ambos formatos para mayor comodidad en YAML:</p>
     * <ul>
     *     <li>display-name</li>
     *     <li>display_name</li>
     * </ul>
     *
     * <p>Si no existe ninguno, se usa el ID como fallback. Esto mantiene compatibilidad
     * con configuraciones antiguas e incluso con IDs que ya tenían formato de color.</p>
     */
    private String parseDisplayName(String scheduleId, ConfigurationSection section) {
        String kebabCase = section.getString("display-name");

        if (kebabCase != null && !kebabCase.isBlank()) {
            return kebabCase;
        }

        String snakeCase = section.getString("display_name");

        if (snakeCase != null && !snakeCase.isBlank()) {
            return snakeCase;
        }

        return scheduleId;
    }

    private DayConfig parseDays(List<String> rawDays) {
        if (rawDays == null || rawDays.isEmpty()) {
            return new DayConfig(true, Set.of(), List.of("TODOS"));
        }

        boolean everyDay = false;
        Set<DayOfWeek> parsedDays = new HashSet<>();
        List<String> displayDays = new ArrayList<>();

        for (String rawDay : rawDays) {
            if (rawDay == null || rawDay.isBlank()) {
                continue;
            }

            String normalized = normalize(rawDay);
            displayDays.add(rawDay);

            if (normalized.equals("TODOS") || normalized.equals("ALL") || normalized.equals("*")) {
                everyDay = true;
                continue;
            }

            DayOfWeek day = parseDay(normalized);

            if (day != null) {
                parsedDays.add(day);
            }
        }

        if (!everyDay && parsedDays.isEmpty()) {
            everyDay = true;
        }

        return new DayConfig(everyDay, parsedDays, displayDays.isEmpty() ? List.of("TODOS") : displayDays);
    }

    private List<LocalTime> parseTimes(String scheduleId, List<String> rawTimes) {
        List<LocalTime> times = new ArrayList<>();

        if (rawTimes == null) {
            return times;
        }

        for (String rawTime : rawTimes) {
            Optional<LocalTime> parsedTime = parseFlexibleTime(rawTime);

            if (parsedTime.isPresent()) {
                times.add(parsedTime.get());
                continue;
            }

            plugin.getLogger().warning(messageService.plain(
                    "logs.invalid-time",
                    "%schedule%", scheduleId,
                    "%time%", String.valueOf(rawTime)
            ));
        }

        return times.stream().distinct().sorted().toList();
    }

    /**
     * Parses user-facing schedule times without forcing server owners to write a single strict style.
     *
     * <p>Supported examples: {@code 18:30}, {@code 8:00}, {@code 8:00 AM},
     * {@code 06:30 PM} and {@code 18:30 PM}. The last form is accepted as a
     * 24-hour time with an informational meridiem suffix because many configuration
     * files use it only for visual clarity.</p>
     */
    private Optional<LocalTime> parseFlexibleTime(String rawTime) {
        if (rawTime == null || rawTime.isBlank()) {
            return Optional.empty();
        }

        String normalized = rawTime.trim()
                .replace('.', ' ')
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT)
                .replace("A M", "AM")
                .replace("P M", "PM");

        Matcher matcher = FLEXIBLE_TIME_PATTERN.matcher(normalized);

        if (!matcher.matches()) {
            return Optional.empty();
        }

        int hour = parseRawNumber(matcher.group(1), -1);
        int minute = parseRawNumber(matcher.group(2), -1);
        String meridiem = matcher.group(3) == null ? "" : matcher.group(3).toUpperCase(Locale.ROOT);

        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            return Optional.empty();
        }

        if (!meridiem.isBlank() && hour <= 12) {
            if (hour == 12) {
                hour = meridiem.equals("AM") ? 0 : 12;
            } else if (meridiem.equals("PM")) {
                hour += 12;
            }
        }

        return Optional.of(LocalTime.of(hour, minute));
    }

    private int parseRawNumber(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private ScheduleRequirements parseRequirements(ConfigurationSection section) {
        if (section == null) {
            return ScheduleRequirements.empty();
        }

        int minPlayersOnline = Math.max(0, section.getInt("min-players-online", 0));
        Set<String> worlds = new LinkedHashSet<>(section.getStringList("worlds"));
        String permission = section.getString("permission", "");

        return new ScheduleRequirements(minPlayersOnline, worlds, permission == null ? "" : permission.trim());
    }

    private List<ScheduledAction> parseConfiguredActions(String scheduleId, ConfigurationSection section) {
        List<?> rawActions = section.getList("actions", List.of());

        if (rawActions != null && !rawActions.isEmpty()) {
            return parseActions(scheduleId, rawActions);
        }

        return parseLegacyCommands(scheduleId, section.getList("commands", List.of()));
    }

    private List<ScheduledAction> parseLegacyCommands(String scheduleId, List<?> rawCommands) {
        List<ScheduledAction> actions = new ArrayList<>();

        for (Object rawCommand : rawCommands) {
            if (rawCommand instanceof String commandText) {
                addCommand(scheduleId, actions, commandText, 0);
                continue;
            }

            if (rawCommand instanceof Map<?, ?> commandMap) {
                Object commandValue = commandMap.get("command");
                Object delayValue = firstObject(commandMap, "delay-seconds", "delay-second", "delaySeconds", "delaySecond", "delay");

                int delaySeconds = parseDelay(delayValue);
                addCommand(scheduleId, actions, String.valueOf(commandValue), delaySeconds);
                continue;
            }

            warnInvalidAction(scheduleId);
        }

        return actions;
    }

    private List<ScheduledAction> parseActions(String scheduleId, List<?> rawActions) {
        List<ScheduledAction> actions = new ArrayList<>();

        for (Object rawAction : rawActions) {
            if (rawAction instanceof String commandText) {
                addCommand(scheduleId, actions, commandText, 0);
                continue;
            }

            if (!(rawAction instanceof Map<?, ?> actionMap)) {
                warnInvalidAction(scheduleId);
                continue;
            }

            ActionType type = guessType(actionMap);
            Object delayValue = firstObject(actionMap, "delay-seconds", "delay-second", "delaySeconds", "delaySecond", "delay");
            int delaySeconds = parseDelay(delayValue);

            switch (type) {
                case COMMAND -> addCommand(scheduleId, actions, string(actionMap.get("command")), delaySeconds);
                case CHAT -> addChat(scheduleId, actions, actionMap, delaySeconds);
                case TITLE -> addTitle(scheduleId, actions, actionMap, delaySeconds);
                case ACTIONBAR -> addActionBar(scheduleId, actions, actionMap, delaySeconds);
                case SOUND -> addSound(scheduleId, actions, actionMap, delaySeconds);
                case BOSSBAR -> addBossBar(scheduleId, actions, actionMap, delaySeconds);
            }
        }

        return actions;
    }

    private ActionType guessType(Map<?, ?> actionMap) {
        Object rawType = actionMap.get("type");

        if (rawType == null) {
            if (actionMap.containsKey("bossbar") || actionMap.containsKey("boss-bar") || actionMap.containsKey("overlay") || actionMap.containsKey("style")) {
                return ActionType.BOSSBAR;
            }
            if (actionMap.containsKey("messages") || actionMap.containsKey("message")) {
                return ActionType.CHAT;
            }
            if (actionMap.containsKey("title") || actionMap.containsKey("subtitle")) {
                return ActionType.TITLE;
            }
            if (actionMap.containsKey("sound")) {
                return ActionType.SOUND;
            }
        }

        return ActionType.from(String.valueOf(rawType));
    }

    private void addCommand(String scheduleId, List<ScheduledAction> actions, String commandText, int delaySeconds) {
        if (commandText == null || commandText.isBlank() || commandText.equals("null")) {
            warnInvalidAction(scheduleId);
            return;
        }

        actions.add(ScheduledAction.command(commandText, Math.max(0, delaySeconds)));
    }

    private void addChat(String scheduleId, List<ScheduledAction> actions, Map<?, ?> actionMap, int delaySeconds) {
        List<String> messages = readStringList(actionMap.get("messages"));
        String message = string(actionMap.get("message"));

        if (messages.isEmpty() && (message == null || message.isBlank())) {
            warnInvalidAction(scheduleId);
            return;
        }

        actions.add(new ScheduledAction(
                ActionType.CHAT,
                Math.max(0, delaySeconds),
                "",
                messages,
                message == null ? "" : message,
                "",
                "",
                10,
                70,
                20,
                "",
                1.0F,
                1.0F,
                null
        ));
    }

    private void addTitle(String scheduleId, List<ScheduledAction> actions, Map<?, ?> actionMap, int delaySeconds) {
        String title = string(actionMap.get("title"));
        String subtitle = string(actionMap.get("subtitle"));

        if ((title == null || title.isBlank()) && (subtitle == null || subtitle.isBlank())) {
            warnInvalidAction(scheduleId);
            return;
        }

        actions.add(new ScheduledAction(
                ActionType.TITLE,
                Math.max(0, delaySeconds),
                "",
                List.of(),
                "",
                title == null ? "" : title,
                subtitle == null ? "" : subtitle,
                parseInt(actionMap.get("fade-in-ticks"), 10),
                parseInt(actionMap.get("stay-ticks"), 70),
                parseInt(actionMap.get("fade-out-ticks"), 20),
                "",
                1.0F,
                1.0F,
                null
        ));
    }

    private void addActionBar(String scheduleId, List<ScheduledAction> actions, Map<?, ?> actionMap, int delaySeconds) {
        String message = string(actionMap.get("message"));

        if (message == null || message.isBlank()) {
            warnInvalidAction(scheduleId);
            return;
        }

        actions.add(new ScheduledAction(
                ActionType.ACTIONBAR,
                Math.max(0, delaySeconds),
                "",
                List.of(),
                message,
                "",
                "",
                10,
                70,
                20,
                "",
                1.0F,
                1.0F,
                null
        ));
    }

    private void addSound(String scheduleId, List<ScheduledAction> actions, Map<?, ?> actionMap, int delaySeconds) {
        String sound = string(actionMap.get("sound"));

        if (sound == null || sound.isBlank()) {
            warnInvalidAction(scheduleId);
            return;
        }

        actions.add(new ScheduledAction(
                ActionType.SOUND,
                Math.max(0, delaySeconds),
                "",
                List.of(),
                "",
                "",
                "",
                10,
                70,
                20,
                sound,
                parseFloat(actionMap.get("volume"), 1.0F),
                parseFloat(actionMap.get("pitch"), 1.0F),
                null
        ));
    }

    private void addBossBar(String scheduleId, List<ScheduledAction> actions, Map<?, ?> actionMap, int delaySeconds) {
        String message = firstString(actionMap, "message", "title", "text", "name");

        if (message == null || message.isBlank()) {
            warnInvalidAction(scheduleId);
            return;
        }

        int durationTicks = parseDurationTicks(actionMap, 100);
        BossBarSettings bossBar = new BossBarSettings(
                message,
                firstStringOrDefault(actionMap, "PURPLE", "color", "bar-color", "barColor"),
                firstStringOrDefault(actionMap, "PROGRESS", "overlay", "style", "bar-style", "barStyle"),
                parseProgress(actionMap.get("progress"), 1.0F),
                durationTicks,
                parseBoolean(firstObject(actionMap, "decrease-progress", "decreaseProgress", "countdown", "countdown-progress"), true),
                parseBossBarFlags(actionMap)
        );

        actions.add(new ScheduledAction(
                ActionType.BOSSBAR,
                Math.max(0, delaySeconds),
                "",
                List.of(),
                message,
                "",
                "",
                10,
                70,
                20,
                "",
                1.0F,
                1.0F,
                bossBar
        ));
    }



    private List<String> parseBossBarFlags(Map<?, ?> actionMap) {
        List<String> flags = new ArrayList<>(readStringList(firstObject(actionMap, "flags", "bar-flags", "barFlags")));

        if (parseBoolean(firstObject(actionMap, "darken-screen", "darkenScreen"), false)) {
            flags.add("DARKEN_SCREEN");
        }

        if (parseBoolean(firstObject(actionMap, "play-boss-music", "playBossMusic", "play-music", "playMusic"), false)) {
            flags.add("PLAY_BOSS_MUSIC");
        }

        if (parseBoolean(firstObject(actionMap, "create-world-fog", "createWorldFog", "world-fog", "worldFog"), false)) {
            flags.add("CREATE_WORLD_FOG");
        }

        return flags.stream()
                .filter(flag -> flag != null && !flag.isBlank())
                .distinct()
                .toList();
    }

    private boolean parseBoolean(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }

        if (value == null) {
            return fallback;
        }

        String normalized = String.valueOf(value).trim().toLowerCase(Locale.ROOT);

        return switch (normalized) {
            case "true", "yes", "y", "on", "1", "enabled" -> true;
            case "false", "no", "n", "off", "0", "disabled" -> false;
            default -> fallback;
        };
    }

    private int parseDurationTicks(Map<?, ?> actionMap, int fallbackTicks) {
        Object ticksValue = firstObject(actionMap, "duration-ticks", "durationTicks", "stay-ticks", "stayTicks");

        if (ticksValue != null) {
            return Math.max(1, parseInt(ticksValue, fallbackTicks));
        }

        Object secondsValue = firstObject(actionMap, "duration-seconds", "durationSeconds", "seconds");

        if (secondsValue != null) {
            return Math.max(1, parseInt(secondsValue, Math.max(1, fallbackTicks / 20)) * 20);
        }

        return Math.max(1, fallbackTicks);
    }

    private float parseProgress(Object value, float fallback) {
        float parsed = parseFloat(value, fallback);

        if (parsed > 1.0F) {
            parsed = parsed / 100.0F;
        }

        return Math.max(0.0F, Math.min(1.0F, parsed));
    }

    private String firstString(Map<?, ?> actionMap, String... keys) {
        Object value = firstObject(actionMap, keys);
        return value == null ? null : String.valueOf(value);
    }

    private String firstStringOrDefault(Map<?, ?> actionMap, String fallback, String... keys) {
        String value = firstString(actionMap, keys);
        return value == null || value.isBlank() ? fallback : value;
    }

    private Object firstObject(Map<?, ?> actionMap, String... keys) {
        for (String key : keys) {
            if (actionMap.containsKey(key)) {
                return actionMap.get(key);
            }
        }

        return null;
    }

    private List<String> readStringList(Object value) {
        if (value == null) {
            return List.of();
        }

        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                result.add(String.valueOf(item));
            }
            return result;
        }

        return List.of(String.valueOf(value));
    }

    private int parseDelay(Object delayValue) {
        return parseInt(delayValue, 0);
    }

    private int parseInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }

        try {
            return Math.max(0, Integer.parseInt(String.valueOf(value)));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private float parseFloat(Object value, float fallback) {
        if (value instanceof Number number) {
            return Math.max(0.0F, number.floatValue());
        }

        try {
            return Math.max(0.0F, Float.parseFloat(String.valueOf(value)));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void warnInvalidAction(String scheduleId) {
        plugin.getLogger().warning(messageService.plain(
                "logs.invalid-action-entry",
                "%schedule%", scheduleId
        ));
    }

    private DayOfWeek parseDay(String normalizedDay) {
        return switch (normalizedDay) {
            case "LUNES", "MONDAY" -> DayOfWeek.MONDAY;
            case "MARTES", "TUESDAY" -> DayOfWeek.TUESDAY;
            case "MIERCOLES", "WEDNESDAY" -> DayOfWeek.WEDNESDAY;
            case "JUEVES", "THURSDAY" -> DayOfWeek.THURSDAY;
            case "VIERNES", "FRIDAY" -> DayOfWeek.FRIDAY;
            case "SABADO", "SATURDAY" -> DayOfWeek.SATURDAY;
            case "DOMINGO", "SUNDAY" -> DayOfWeek.SUNDAY;
            default -> null;
        };
    }

    private Set<String> lookupVariants(String value) {
        Set<String> keys = new LinkedHashSet<>();

        if (value == null || value.isBlank()) {
            return keys;
        }

        addLookupKey(keys, value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_'));
        addLookupKey(keys, placeholderKey(value));

        List<String> snapshot = List.copyOf(keys);
        for (String key : snapshot) {
            addLookupKey(keys, compactKey(key));

            if (key.startsWith("boss_")) {
                String withoutBoss = key.substring("boss_".length());
                addLookupKey(keys, withoutBoss);
                addLookupKey(keys, compactKey(withoutBoss));
            } else if (!key.isBlank()) {
                addLookupKey(keys, "boss_" + key);
                addLookupKey(keys, "boss" + compactKey(key));
            }
        }

        return keys;
    }

    private void addLookupKey(Set<String> keys, String key) {
        if (key == null || key.isBlank()) {
            return;
        }

        keys.add(key.toLowerCase(Locale.ROOT)
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", ""));
    }

    private String compactKey(String input) {
        return placeholderKey(input).replace("_", "");
    }

    /**
     * Converts an ID or display-name to a safe key for commands and PlaceholderAPI.
     *
     * <p>Example: {@code "&#FF3300&lDragón Infernal"} -> {@code dragon_infernal}.</p>
     */
    private String placeholderKey(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }

        String plain = TextFormatter.plain(input);
        String withoutAccents = Normalizer.normalize(plain, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return withoutAccents
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }

    private String normalize(String value) {
        return value.trim()
                .toUpperCase(Locale.ROOT)
                .replace("Á", "A")
                .replace("É", "E")
                .replace("Í", "I")
                .replace("Ó", "O")
                .replace("Ú", "U");
    }

    private record DayConfig(boolean everyDay, Set<DayOfWeek> days, List<String> displayDays) {
    }
}
