package dev.keraune.commanddelay.scheduler;

import dev.keraune.commanddelay.CommandDelay;
import dev.keraune.commanddelay.config.MessageService;
import dev.keraune.commanddelay.config.PluginSettings;
import dev.keraune.commanddelay.data.ExecutionHistory;
import dev.keraune.commanddelay.model.ActionType;
import dev.keraune.commanddelay.model.NextActionSnapshot;
import dev.keraune.commanddelay.model.ScheduleDefinition;
import dev.keraune.commanddelay.model.ScheduleRequirements;
import dev.keraune.commanddelay.model.ScheduledAction;
import dev.keraune.commanddelay.util.PlaceholderResolver;
import dev.keraune.commanddelay.util.TextFormatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Servicio encargado de revisar horarios y ejecutar acciones.
 *
 * <p>Todas las acciones se ejecutan en el hilo principal del servidor, usando el scheduler
 * de Bukkit/Paper. Esto evita problemas de concurrencia con comandos, jugadores,
 * títulos, sonidos y mensajes.</p>
 *
 * <p>El runner también controla ejecuciones activas. Si un horario tiene acciones con delay,
 * el mismo horario no volverá a iniciar hasta que termine la última acción pendiente.
 * Esto evita duplicados cuando se recarga el plugin o cuando existen acciones largas.</p>
 */
public final class ScheduleRunner {

    /** Detecta placeholders con formato %placeholder%. */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("%[^%\\s]+%");

    private final CommandDelay plugin;
    private final MessageService messageService;
    private final ScheduleRepository scheduleRepository;
    private final ExecutionHistory executionHistory;

    /** Tarea principal que revisa horarios fijos. */
    private BukkitTask task;

    /** Configuración cargada en memoria para evitar leer YAML en cada tick. */
    private PluginSettings settings;

    /** Horarios actualmente ejecutándose. Previene solapamientos y duplicados. */
    private final Set<String> activeSchedules = new HashSet<>();

    /** Fecha y hora de inicio de cada ejecución activa. Se usa para placeholders de cuenta regresiva. */
    private final Map<String, ZonedDateTime> activeScheduleStarts = new HashMap<>();

    /** Acciones con delay pendientes. Se cancelan al hacer reload/disable para evitar duplicados. */
    private final Map<String, List<BukkitTask>> pendingActionTasks = new HashMap<>();

    public ScheduleRunner(
            CommandDelay plugin,
            MessageService messageService,
            ScheduleRepository scheduleRepository,
            ExecutionHistory executionHistory
    ) {
        this.plugin = plugin;
        this.messageService = messageService;
        this.scheduleRepository = scheduleRepository;
        this.executionHistory = executionHistory;
    }

    public void start(PluginSettings settings) {
        stop();

        this.settings = settings;
        long intervalTicks = Math.max(1L, settings.checkIntervalSeconds()) * 20L;

        this.task = Bukkit.getScheduler().runTaskTimer(
                plugin,
                this::tick,
                20L,
                intervalTicks
        );

        plugin.getLogger().info(messageService.plain(
                "logs.scheduler-started",
                "%interval%", String.valueOf(settings.checkIntervalSeconds()),
                "%timezone%", settings.zoneId().getId()
        ));
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }

        cancelPendingActions();
        plugin.getLogger().info(messageService.plain("logs.scheduler-stopped"));
    }

    public void runNow(ScheduleDefinition schedule) {
        executeSchedule(schedule);
    }

    /**
     * Devuelve la configuración activa del runner.
     *
     * <p>Se expone para PlaceholderAPI sin obligar a leer config.yml en cada consulta.</p>
     */
    public PluginSettings settings() {
        return settings;
    }

    /**
     * Calcula la próxima acción global entre todos los horarios habilitados.
     */
    public Optional<NextActionSnapshot> nextAction() {
        if (settings == null) {
            return Optional.empty();
        }

        return scheduleRepository.all().stream()
                .filter(ScheduleDefinition::enabled)
                .map(this::nextActionForSchedule)
                .flatMap(Optional::stream)
                .min(Comparator.comparing(NextActionSnapshot::dueAt));
    }

    /**
     * Calcula la próxima acción de un horario específico.
     */
    public Optional<NextActionSnapshot> nextAction(String scheduleId) {
        if (settings == null || scheduleId == null || scheduleId.isBlank()) {
            return Optional.empty();
        }

        return scheduleRepository.find(scheduleId)
                .filter(ScheduleDefinition::enabled)
                .flatMap(this::nextActionForSchedule);
    }

    private Optional<NextActionSnapshot> nextActionForSchedule(ScheduleDefinition schedule) {
        ZonedDateTime now = ZonedDateTime.now(settings.zoneId());
        ZonedDateTime activeStart = activeScheduleStarts.get(schedule.id());

        if (activeStart != null && isActive(schedule.id())) {
            return nextActionFromStart(schedule, activeStart, now, true);
        }

        return schedule.nextExecution(settings.zoneId())
                .flatMap(start -> nextActionFromStart(schedule, start, now, false));
    }

    private Optional<NextActionSnapshot> nextActionFromStart(
            ScheduleDefinition schedule,
            ZonedDateTime start,
            ZonedDateTime now,
            boolean active
    ) {
        return schedule.actions().stream()
                .map(action -> new NextActionSnapshot(
                        schedule.id(),
                        action.type(),
                        start.plusSeconds(action.delaySeconds()),
                        active
                ))
                .filter(snapshot -> !snapshot.dueAt().isBefore(now))
                .min(Comparator.comparing(NextActionSnapshot::dueAt));
    }

    private void tick() {
        if (settings == null) {
            return;
        }

        ZonedDateTime now = ZonedDateTime.now(settings.zoneId());
        LocalDate currentDate = now.toLocalDate();
        LocalTime currentTime = now.toLocalTime().truncatedTo(ChronoUnit.MINUTES);

        for (ScheduleDefinition schedule : scheduleRepository.all()) {
            if (!schedule.enabled()) {
                continue;
            }

            if (!schedule.canRunOn(now.getDayOfWeek())) {
                continue;
            }

            if (!schedule.times().contains(currentTime)) {
                continue;
            }

            if (executionHistory.hasExecuted(schedule.id(), currentDate, currentTime)) {
                continue;
            }

            // Si el horario todavía está procesando acciones anteriores, esta ocurrencia se marca
            // como atendida para no repetir logs ni intentar reiniciarla varias veces durante el minuto.
            if (isActive(schedule.id())) {
                executionHistory.markExecuted(schedule.id(), currentDate, currentTime);
                plugin.getLogger().info(messageService.plain(
                        "logs.schedule-already-active",
                        "%schedule%", schedule.id()
                ));
                continue;
            }

            executionHistory.markExecuted(schedule.id(), currentDate, currentTime);
            executeSchedule(schedule);
        }
    }

    private void executeSchedule(ScheduleDefinition schedule) {
        if (!beginExecution(schedule.id())) {
            plugin.getLogger().info(messageService.plain(
                    "logs.schedule-already-active",
                    "%schedule%", schedule.id()
            ));
            return;
        }

        registerActiveStart(schedule.id());

        List<Player> targets = eligiblePlayers(schedule);

        if (!requirementsSatisfied(schedule, targets)) {
            logRequirementsFailed(schedule, targets.size());
            finishExecution(schedule.id());
            return;
        }

        plugin.getLogger().info(messageService.plain(
                "logs.executing-schedule",
                "%schedule%", schedule.id()
        ));

        if (schedule.actions().isEmpty()) {
            finishExecution(schedule.id());
            return;
        }

        AtomicInteger remainingActions = new AtomicInteger(schedule.actions().size());

        for (ScheduledAction action : schedule.actions()) {
            if (action.delaySeconds() <= 0) {
                runActionSafely(schedule, action, targets);
                finishAction(schedule.id(), remainingActions);
                continue;
            }

            plugin.getLogger().info(messageService.plain(
                    "logs.delayed-action",
                    "%delay%", String.valueOf(action.delaySeconds()),
                    "%action%", action.type().name(),
                    "%schedule%", schedule.id()
            ));

            BukkitTask delayedTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try {
                    runActionSafely(schedule, action, eligiblePlayers(schedule));
                } finally {
                    finishAction(schedule.id(), remainingActions);
                }
            }, action.delayTicks());

            registerPendingAction(schedule.id(), delayedTask);
        }
    }

    private void runActionSafely(ScheduleDefinition schedule, ScheduledAction action, List<Player> targets) {
        try {
            executeAction(schedule, action, targets);
        } catch (RuntimeException exception) {
            plugin.getLogger().severe(messageService.plain(
                    "logs.action-error",
                    "%schedule%", schedule.id(),
                    "%action%", action.type().name()
            ));
            exception.printStackTrace();
        }
    }

    private boolean beginExecution(String scheduleId) {
        return activeSchedules.add(scheduleId);
    }

    private void registerActiveStart(String scheduleId) {
        if (settings == null) {
            activeScheduleStarts.put(scheduleId, ZonedDateTime.now());
            return;
        }

        activeScheduleStarts.put(scheduleId, ZonedDateTime.now(settings.zoneId()));
    }

    private boolean isActive(String scheduleId) {
        return activeSchedules.contains(scheduleId);
    }

    private void finishAction(String scheduleId, AtomicInteger remainingActions) {
        if (remainingActions.decrementAndGet() > 0) {
            return;
        }

        finishExecution(scheduleId);
    }

    private void finishExecution(String scheduleId) {
        activeSchedules.remove(scheduleId);
        activeScheduleStarts.remove(scheduleId);
        pendingActionTasks.remove(scheduleId);
        plugin.getLogger().info(messageService.plain(
                "logs.schedule-finished",
                "%schedule%", scheduleId
        ));
    }

    private void registerPendingAction(String scheduleId, BukkitTask task) {
        pendingActionTasks.computeIfAbsent(scheduleId, ignored -> new ArrayList<>()).add(task);
    }

    private void cancelPendingActions() {
        for (List<BukkitTask> tasks : pendingActionTasks.values()) {
            for (BukkitTask pendingTask : tasks) {
                if (pendingTask != null && !pendingTask.isCancelled()) {
                    pendingTask.cancel();
                }
            }
        }

        pendingActionTasks.clear();
        activeSchedules.clear();
        activeScheduleStarts.clear();
    }

    private void logRequirementsFailed(ScheduleDefinition schedule, int onlineTargets) {
        plugin.getLogger().info(messageService.plain(
                "logs.requirements-failed",
                "%schedule%", schedule.id(),
                "%online%", String.valueOf(onlineTargets),
                "%min_players%", String.valueOf(schedule.requirements().minPlayersOnline())
        ));
    }

    private boolean requirementsSatisfied(ScheduleDefinition schedule, List<Player> targets) {
        ScheduleRequirements requirements = schedule.requirements();
        return targets.size() >= requirements.minPlayersOnline();
    }

    private List<Player> eligiblePlayers(ScheduleDefinition schedule) {
        Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();
        ScheduleRequirements requirements = schedule.requirements();

        List<Player> players = new ArrayList<>();

        for (Player player : onlinePlayers) {
            if (requirements.matches(player)) {
                players.add(player);
            }
        }

        return players;
    }

    private void executeAction(ScheduleDefinition schedule, ScheduledAction action, List<Player> targets) {
        if (!requirementsSatisfied(schedule, targets)) {
            logRequirementsFailed(schedule, targets.size());
            return;
        }

        if (action.type() == ActionType.COMMAND) {
            executeCommand(schedule, action, targets);
            return;
        }

        if (targets.isEmpty()) {
            return;
        }

        switch (action.type()) {
            case CHAT -> sendChat(schedule, action, targets);
            case TITLE -> sendTitle(schedule, action, targets);
            case ACTIONBAR -> sendActionBar(schedule, action, targets);
            case SOUND -> playSound(action, targets);
            case COMMAND -> executeCommand(schedule, action, targets);
        }
    }

    private void executeCommand(ScheduleDefinition schedule, ScheduledAction action, List<Player> targets) {
        String rawCommand = action.command() == null ? "" : action.command().trim();

        if (rawCommand.isBlank()) {
            return;
        }

        if (shouldRunCommandPerPlayer(schedule, rawCommand, targets)) {
            for (Player player : targets) {
                String command = normalizeCommand(rawCommand, schedule, player, targets.size());

                if (!command.isBlank()) {
                    dispatchCommand(command);
                }
            }
            return;
        }

        String command = normalizeCommand(rawCommand, schedule, null, targets.size());

        if (command.isBlank()) {
            return;
        }

        dispatchCommand(command);
    }

    /**
     * Determina si un comando debe ejecutarse una vez por jugador objetivo.
     *
     * <p>Esto permite que PlaceholderAPI funcione también dentro de comandos de consola.
     * Por ejemplo, si el comando contiene {@code %player_name%}, el resultado será distinto
     * para cada jugador y el plugin ejecutará el comando una vez por jugador. Si el comando
     * solo contiene placeholders globales o internos, se ejecutará una sola vez.</p>
     */
    private boolean shouldRunCommandPerPlayer(ScheduleDefinition schedule, String rawCommand, List<Player> targets) {
        if (targets.isEmpty() || !PLACEHOLDER_PATTERN.matcher(rawCommand).find()) {
            return false;
        }

        String consoleCommand = normalizeCommand(rawCommand, schedule, null, targets.size());
        String playerCommand = normalizeCommand(rawCommand, schedule, targets.getFirst(), targets.size());

        return !consoleCommand.equals(playerCommand);
    }

    private void sendChat(ScheduleDefinition schedule, ScheduledAction action, List<Player> targets) {
        List<String> lines = !action.messages().isEmpty() ? action.messages() : List.of(action.message());

        for (Player player : targets) {
            Map<String, String> placeholders = placeholders(schedule, player, targets.size());
            for (String line : lines) {
                player.sendMessage(component(line, player, placeholders));
            }
        }
    }

    private void sendTitle(ScheduleDefinition schedule, ScheduledAction action, List<Player> targets) {
        Title.Times times = Title.Times.times(
                ticks(action.fadeInTicks()),
                ticks(action.stayTicks()),
                ticks(action.fadeOutTicks())
        );

        for (Player player : targets) {
            Map<String, String> placeholders = placeholders(schedule, player, targets.size());
            Component title = component(action.title(), player, placeholders);
            Component subtitle = component(action.subtitle(), player, placeholders);
            player.showTitle(Title.title(title, subtitle, times));
        }
    }

    private void sendActionBar(ScheduleDefinition schedule, ScheduledAction action, List<Player> targets) {
        for (Player player : targets) {
            Map<String, String> placeholders = placeholders(schedule, player, targets.size());
            player.sendActionBar(component(action.message(), player, placeholders));
        }
    }

    private void playSound(ScheduledAction action, List<Player> targets) {
        for (Player player : targets) {
            player.playSound(player.getLocation(), action.sound(), SoundCategory.MASTER, action.volume(), action.pitch());
        }
    }

    private Component component(String text, Player player, Map<String, String> placeholders) {
        String resolved = PlaceholderResolver.resolve(
                plugin,
                player,
                text,
                settings != null && settings.placeholderApiEnabled(),
                placeholders
        );
        return TextFormatter.component(resolved);
    }

    private String normalizeCommand(String rawCommand, ScheduleDefinition schedule, Player player, int targetCount) {
        String command = rawCommand == null ? "" : rawCommand.trim();
        command = PlaceholderResolver.resolve(
                plugin,
                player,
                command,
                settings != null && settings.placeholderApiEnabled(),
                placeholders(schedule, player, targetCount)
        );

        if (settings != null && settings.removeLeadingSlash()) {
            while (command.startsWith("/")) {
                command = command.substring(1).trim();
            }
        }

        if (settings != null && settings.translateCommandColors()) {
            command = TextFormatter.legacy(command);
        }

        return command;
    }

    private Map<String, String> placeholders(ScheduleDefinition schedule, Player player, int targetCount) {
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("%schedule%", schedule.id());
        placeholders.put("%event_id%", schedule.id());
        placeholders.put("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()));
        placeholders.put("%targets%", String.valueOf(targetCount));
        placeholders.put("%min_players%", String.valueOf(schedule.requirements().minPlayersOnline()));

        if (player != null) {
            placeholders.put("%player%", player.getName());
            placeholders.put("%world%", player.getWorld().getName());
            placeholders.put("%x%", String.valueOf(player.getLocation().getBlockX()));
            placeholders.put("%y%", String.valueOf(player.getLocation().getBlockY()));
            placeholders.put("%z%", String.valueOf(player.getLocation().getBlockZ()));
        } else {
            placeholders.put("%player%", "");
            placeholders.put("%world%", "");
            placeholders.put("%x%", "");
            placeholders.put("%y%", "");
            placeholders.put("%z%", "");
        }

        return placeholders;
    }

    private Duration ticks(int ticks) {
        return Duration.ofMillis(Math.max(0, ticks) * 50L);
    }

    private void dispatchCommand(String command) {
        plugin.getLogger().info(messageService.plain(
                "logs.dispatch-command",
                "%command%", command
        ));

        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        } catch (Exception exception) {
            plugin.getLogger().severe(messageService.plain(
                    "logs.command-error",
                    "%command%", command
            ));
            exception.printStackTrace();
        }
    }
}
