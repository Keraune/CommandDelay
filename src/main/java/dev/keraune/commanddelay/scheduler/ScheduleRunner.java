package dev.keraune.commanddelay.scheduler;

import dev.keraune.commanddelay.CommandDelay;
import dev.keraune.commanddelay.config.MessageService;
import dev.keraune.commanddelay.config.PluginSettings;
import dev.keraune.commanddelay.data.ExecutionHistory;
import dev.keraune.commanddelay.model.ActionDelayMode;
import dev.keraune.commanddelay.model.ActionType;
import dev.keraune.commanddelay.model.BossBarSettings;
import dev.keraune.commanddelay.model.NextActionSnapshot;
import dev.keraune.commanddelay.model.ScheduleDefinition;
import dev.keraune.commanddelay.model.ScheduleRequirements;
import dev.keraune.commanddelay.model.ScheduledAction;
import dev.keraune.commanddelay.util.PlaceholderResolver;
import dev.keraune.commanddelay.util.TextFormatter;
import net.kyori.adventure.bossbar.BossBar;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.Comparator;
import java.util.Optional;
import java.util.EnumSet;
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

    private static final DateTimeFormatter STATIC_TIME_24_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter STATIC_TIME_24_AMPM_FORMAT = DateTimeFormatter.ofPattern("HH:mm a", Locale.US);
    private static final DateTimeFormatter STATIC_TIME_12_FORMAT = DateTimeFormatter.ofPattern("h:mm a", Locale.US);

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

    /** Pending delayed actions. They are cancelled on reload/disable to prevent duplicates. */
    private final Map<String, List<BukkitTask>> pendingActionTasks = new HashMap<>();

    /** Active boss bars shown by scheduled actions. They are hidden on reload/disable. */
    private final Map<String, List<ActiveBossBar>> activeBossBars = new HashMap<>();

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
        List<NextActionSnapshot> snapshots = new ArrayList<>();
        long cumulativeDelaySeconds = 0L;

        for (ScheduledAction action : schedule.actions()) {
            if (schedule.actionDelayMode() == ActionDelayMode.SEQUENTIAL) {
                cumulativeDelaySeconds += Math.max(0, action.delaySeconds());
                snapshots.add(new NextActionSnapshot(
                        schedule.id(),
                        action.type(),
                        start.plusSeconds(cumulativeDelaySeconds),
                        active
                ));
                continue;
            }

            snapshots.add(new NextActionSnapshot(
                    schedule.id(),
                    action.type(),
                    start.plusSeconds(action.delaySeconds()),
                    active
            ));
        }

        return snapshots.stream()
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

        if (schedule.actionDelayMode() == ActionDelayMode.SEQUENTIAL) {
            executeSequentialAction(schedule, 0);
            return;
        }

        executeAbsoluteActions(schedule, targets);
    }

    private void executeAbsoluteActions(ScheduleDefinition schedule, List<Player> initialTargets) {
        AtomicInteger remainingActions = new AtomicInteger(schedule.actions().size());

        for (ScheduledAction action : schedule.actions()) {
            if (action.delaySeconds() <= 0) {
                runActionSafely(schedule, action, initialTargets);
                finishAction(schedule.id(), remainingActions);
                continue;
            }

            logDelayedAction(schedule, action);

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

    private void executeSequentialAction(ScheduleDefinition schedule, int actionIndex) {
        if (!isActive(schedule.id())) {
            return;
        }

        if (actionIndex >= schedule.actions().size()) {
            finishExecution(schedule.id());
            return;
        }

        ScheduledAction action = schedule.actions().get(actionIndex);

        Runnable executor = () -> {
            try {
                runActionSafely(schedule, action, eligiblePlayers(schedule));
            } finally {
                executeSequentialAction(schedule, actionIndex + 1);
            }
        };

        if (action.delaySeconds() <= 0) {
            executor.run();
            return;
        }

        logDelayedAction(schedule, action);

        BukkitTask delayedTask = Bukkit.getScheduler().runTaskLater(plugin, executor, action.delayTicks());
        registerPendingAction(schedule.id(), delayedTask);
    }

    private void logDelayedAction(ScheduleDefinition schedule, ScheduledAction action) {
        plugin.getLogger().info(messageService.plain(
                "logs.delayed-action",
                "%delay%", String.valueOf(action.delaySeconds()),
                "%action%", action.type().name(),
                "%schedule%", schedule.id()
        ));
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
        hideActiveBossBars();
        activeSchedules.clear();
        activeScheduleStarts.clear();
    }

    private void registerActiveBossBar(String scheduleId, ActiveBossBar activeBossBar) {
        activeBossBars.computeIfAbsent(scheduleId, ignored -> new ArrayList<>()).add(activeBossBar);
    }

    private void hideBossBar(String scheduleId, Player player, BossBar bossBar) {
        List<ActiveBossBar> views = activeBossBars.get(scheduleId);

        if (views == null) {
            player.hideBossBar(bossBar);
            return;
        }

        views.removeIf(view -> {
            boolean matches = view.player().getUniqueId().equals(player.getUniqueId()) && view.bossBar() == bossBar;

            if (matches) {
                cancelTask(view.progressTask());
                view.player().hideBossBar(view.bossBar());
            }

            return matches;
        });

        if (views.isEmpty()) {
            activeBossBars.remove(scheduleId);
        }
    }

    private void hideActiveBossBars() {
        for (List<ActiveBossBar> views : activeBossBars.values()) {
            for (ActiveBossBar view : views) {
                cancelTask(view.hideTask());
                cancelTask(view.progressTask());
                view.player().hideBossBar(view.bossBar());
            }
        }

        activeBossBars.clear();
    }

    private void cancelTask(BukkitTask taskToCancel) {
        if (taskToCancel != null && !taskToCancel.isCancelled()) {
            taskToCancel.cancel();
        }
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
            case BOSSBAR -> sendBossBar(schedule, action, targets);
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

    private void sendBossBar(ScheduleDefinition schedule, ScheduledAction action, List<Player> targets) {
        BossBarSettings bossBarSettings = action.bossBar();

        if (bossBarSettings == null) {
            return;
        }

        BossBar.Color color = bossBarColor(bossBarSettings.color());
        BossBar.Overlay overlay = bossBarOverlay(bossBarSettings.overlay());
        Set<BossBar.Flag> flags = bossBarFlags(bossBarSettings.flags());
        long durationTicks = Math.max(1L, bossBarSettings.durationTicks());
        float initialProgress = Math.max(0.0F, Math.min(1.0F, bossBarSettings.progress()));

        for (Player player : targets) {
            Map<String, String> placeholders = placeholders(schedule, player, targets.size());
            Component name = component(bossBarSettings.message(), player, placeholders);
            BossBar bossBar = BossBar.bossBar(name, initialProgress, color, overlay);

            for (BossBar.Flag flag : flags) {
                bossBar.addFlag(flag);
            }

            player.showBossBar(bossBar);

            BukkitTask progressTask = createBossBarProgressTask(bossBarSettings, bossBar, durationTicks, initialProgress);
            BukkitTask hideTask = Bukkit.getScheduler().runTaskLater(
                    plugin,
                    () -> hideBossBar(schedule.id(), player, bossBar),
                    durationTicks
            );

            registerActiveBossBar(schedule.id(), new ActiveBossBar(player, bossBar, hideTask, progressTask));
        }
    }

    private BukkitTask createBossBarProgressTask(
            BossBarSettings bossBarSettings,
            BossBar bossBar,
            long durationTicks,
            float initialProgress
    ) {
        if (!bossBarSettings.decreaseProgress() || initialProgress <= 0.0F) {
            return null;
        }

        return Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            private long elapsedTicks = 0L;

            @Override
            public void run() {
                elapsedTicks++;
                float remainingRatio = Math.max(0.0F, (durationTicks - elapsedTicks) / (float) durationTicks);
                bossBar.progress(Math.max(0.0F, initialProgress * remainingRatio));
            }
        }, 1L, 1L);
    }

    private BossBar.Color bossBarColor(String rawColor) {
        String normalized = normalizeEnum(rawColor);

        return switch (normalized) {
            case "PINK" -> BossBar.Color.PINK;
            case "BLUE" -> BossBar.Color.BLUE;
            case "RED" -> BossBar.Color.RED;
            case "GREEN" -> BossBar.Color.GREEN;
            case "YELLOW" -> BossBar.Color.YELLOW;
            case "WHITE" -> BossBar.Color.WHITE;
            case "PURPLE" -> BossBar.Color.PURPLE;
            default -> BossBar.Color.PURPLE;
        };
    }

    private BossBar.Overlay bossBarOverlay(String rawOverlay) {
        String normalized = normalizeEnum(rawOverlay);

        return switch (normalized) {
            case "SOLID", "PROGRESS" -> BossBar.Overlay.PROGRESS;
            case "SEGMENTED_6", "NOTCHED_6", "NOTCHES_6", "6" -> BossBar.Overlay.NOTCHED_6;
            case "SEGMENTED_10", "NOTCHED_10", "NOTCHES_10", "10" -> BossBar.Overlay.NOTCHED_10;
            case "SEGMENTED_12", "NOTCHED_12", "NOTCHES_12", "12" -> BossBar.Overlay.NOTCHED_12;
            case "SEGMENTED_20", "NOTCHED_20", "NOTCHES_20", "20" -> BossBar.Overlay.NOTCHED_20;
            default -> BossBar.Overlay.PROGRESS;
        };
    }

    private Set<BossBar.Flag> bossBarFlags(List<String> rawFlags) {
        EnumSet<BossBar.Flag> flags = EnumSet.noneOf(BossBar.Flag.class);

        for (String rawFlag : rawFlags) {
            String normalized = normalizeEnum(rawFlag);

            switch (normalized) {
                case "DARKEN_SCREEN", "DARKEN", "DARK" -> flags.add(BossBar.Flag.DARKEN_SCREEN);
                case "PLAY_BOSS_MUSIC", "BOSS_MUSIC", "PLAY_MUSIC", "MUSIC" -> flags.add(BossBar.Flag.PLAY_BOSS_MUSIC);
                case "CREATE_WORLD_FOG", "WORLD_FOG", "FOG" -> flags.add(BossBar.Flag.CREATE_WORLD_FOG);
                default -> {
                    // Unknown flags are ignored to keep reloads safe on user-edited YAML files.
                }
            }
        }

        return flags;
    }

    private String normalizeEnum(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        return value.trim()
                .toUpperCase(java.util.Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
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
        placeholders.put("%schedule_display_name%", TextFormatter.legacy(schedule.displayName()));
        placeholders.put("%schedule_name%", TextFormatter.legacy(schedule.displayName()));
        placeholders.put("%event_name%", TextFormatter.legacy(schedule.displayName()));
        placeholders.put("%schedule_plain_name%", TextFormatter.plain(schedule.displayName()));
        placeholders.put("%schedule_raw_name%", schedule.displayName());
        placeholders.put("%schedule_time_24%", scheduleTime(schedule, "time_24"));
        placeholders.put("%schedule_time_24_ampm%", scheduleTime(schedule, "time_24_ampm"));
        placeholders.put("%schedule_time_12%", scheduleTime(schedule, "time_12"));
        placeholders.put("%schedule_times_24%", scheduleTime(schedule, "times_24"));
        placeholders.put("%schedule_times_24_ampm%", scheduleTime(schedule, "times_24_ampm"));
        placeholders.put("%schedule_times_12%", scheduleTime(schedule, "times_12"));
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


    private String scheduleTime(ScheduleDefinition schedule, String type) {
        List<LocalTime> times = schedule.times();

        if (times.isEmpty()) {
            return "";
        }

        if (!type.startsWith("times_")) {
            return formatStaticTime(times.getFirst(), type);
        }

        return String.join(", ", times.stream()
                .map(time -> formatStaticTime(time, type))
                .toList());
    }

    private String formatStaticTime(LocalTime time, String type) {
        return switch (type) {
            case "time_12", "times_12" -> time.format(STATIC_TIME_12_FORMAT);
            case "time_24_ampm", "times_24_ampm" -> time.format(STATIC_TIME_24_AMPM_FORMAT);
            default -> time.format(STATIC_TIME_24_FORMAT);
        };
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

    private record ActiveBossBar(Player player, BossBar bossBar, BukkitTask hideTask, BukkitTask progressTask) {
    }
}
