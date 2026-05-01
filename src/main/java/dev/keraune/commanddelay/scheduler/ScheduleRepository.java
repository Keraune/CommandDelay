package dev.keraune.commanddelay.scheduler;

import dev.keraune.commanddelay.CommandDelay;
import dev.keraune.commanddelay.config.MessageService;
import dev.keraune.commanddelay.model.ActionType;
import dev.keraune.commanddelay.model.ScheduleDefinition;
import dev.keraune.commanddelay.model.ScheduleRequirements;
import dev.keraune.commanddelay.model.ScheduledAction;
import org.bukkit.configuration.ConfigurationSection;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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

/**
 * Carga y mantiene en memoria los horarios definidos en config.yml.
 *
 * <p>El repositorio valida la configuración una vez en cada reload. Así, el scheduler
 * trabaja con objetos limpios y no accede al YAML en cada revisión.</p>
 */
public final class ScheduleRepository {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

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
        return Optional.ofNullable(schedules.get(id));
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
                enabled,
                dayConfig.everyDay(),
                dayConfig.days(),
                times,
                actions,
                requirements,
                dayConfig.displayDays(),
                times.stream().map(TIME_FORMATTER::format).toList()
        ));
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
            try {
                times.add(LocalTime.parse(rawTime, TIME_FORMATTER));
            } catch (DateTimeParseException | NullPointerException exception) {
                plugin.getLogger().warning(messageService.plain(
                        "logs.invalid-time",
                        "%schedule%", scheduleId,
                        "%time%", String.valueOf(rawTime)
                ));
            }
        }

        return times.stream().distinct().sorted().toList();
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
                Object delayValue = commandMap.containsKey("delay-seconds") ? commandMap.get("delay-seconds") : 0;

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
            Object delayValue = actionMap.containsKey("delay-seconds") ? actionMap.get("delay-seconds") : 0;
            int delaySeconds = parseDelay(delayValue);

            switch (type) {
                case COMMAND -> addCommand(scheduleId, actions, string(actionMap.get("command")), delaySeconds);
                case CHAT -> addChat(scheduleId, actions, actionMap, delaySeconds);
                case TITLE -> addTitle(scheduleId, actions, actionMap, delaySeconds);
                case ACTIONBAR -> addActionBar(scheduleId, actions, actionMap, delaySeconds);
                case SOUND -> addSound(scheduleId, actions, actionMap, delaySeconds);
            }
        }

        return actions;
    }

    private ActionType guessType(Map<?, ?> actionMap) {
        Object rawType = actionMap.get("type");

        if (rawType == null) {
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
                1.0F
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
                1.0F
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
                1.0F
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
                parseFloat(actionMap.get("pitch"), 1.0F)
        ));
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
