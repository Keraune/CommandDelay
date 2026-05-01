package dev.keraune.commanddelay.command;

import dev.keraune.commanddelay.CommandDelay;
import dev.keraune.commanddelay.config.MessageService;
import dev.keraune.commanddelay.config.PluginSettings;
import dev.keraune.commanddelay.model.ScheduleDefinition;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import dev.keraune.commanddelay.util.TextFormatter;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Comando principal del plugin.
 *
 * <p>Incluye autocompletado y delega la lógica real a los servicios del plugin.
 * Esto evita que el comando se convierta en una clase gigante difícil de mantener.</p>
 */
public final class CommandDelayCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("help", "reload", "list", "info", "run", "next");

    private final CommandDelay plugin;

    public CommandDelayCommand(CommandDelay plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase(Locale.ROOT);

        switch (subCommand) {
            case "help" -> sendHelp(sender);
            case "reload" -> reload(sender);
            case "list" -> list(sender);
            case "info" -> info(sender, args);
            case "run" -> run(sender, args);
            case "next" -> next(sender);
            default -> messages().send(sender, "commands.unknown-subcommand");
        }

        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("run"))) {
            return filter(scheduleIds(), args[1]);
        }

        return List.of();
    }

    private void sendHelp(CommandSender sender) {
        if (!hasPermission(sender, "commanddelay.use")) {
            return;
        }

        messages().sendList(sender, "commands.help");
    }

    private void reload(CommandSender sender) {
        if (!hasPermission(sender, "commanddelay.reload")) {
            return;
        }

        plugin.reloadPlugin(true);
        messages().send(
                sender,
                "commands.reload-success",
                "%total%", String.valueOf(plugin.scheduleRepository().size())
        );
    }

    private void list(CommandSender sender) {
        if (!hasPermission(sender, "commanddelay.list")) {
            return;
        }

        if (plugin.scheduleRepository().size() == 0) {
            messages().send(sender, "commands.list-empty");
            return;
        }

        messages().send(
                sender,
                "commands.list-header",
                "%total%", String.valueOf(plugin.scheduleRepository().size())
        );

        for (ScheduleDefinition schedule : plugin.scheduleRepository().all()) {
            messages().send(
                    sender,
                    "commands.list-entry",
                    "%schedule%", schedule.id(),
                    "%schedule_display_name%", TextFormatter.legacy(schedule.displayName()),
                    "%enabled%", enabledText(schedule),
                    "%days%", String.join(", ", schedule.displayDays()),
                    "%times%", String.join(", ", schedule.displayTimes()),
                    "%commands%", String.valueOf(schedule.commands().size())
            );
        }
    }

    private void info(CommandSender sender, String[] args) {
        if (!hasPermission(sender, "commanddelay.info")) {
            return;
        }

        if (args.length < 2) {
            messages().send(sender, "commands.info-usage");
            return;
        }

        String scheduleId = args[1];
        Optional<ScheduleDefinition> optionalSchedule = plugin.scheduleRepository().find(scheduleId);

        if (optionalSchedule.isEmpty()) {
            messages().send(sender, "commands.info-not-found", "%schedule%", scheduleId);
            return;
        }

        ScheduleDefinition schedule = optionalSchedule.get();

        messages().sendList(
                sender,
                "commands.info",
                "%schedule%", schedule.id(),
                "%schedule_display_name%", TextFormatter.legacy(schedule.displayName()),
                "%enabled%", enabledText(schedule),
                "%days%", String.join(", ", schedule.displayDays()),
                "%times%", String.join(", ", schedule.displayTimes()),
                "%commands%", String.valueOf(schedule.commands().size()),
                "%next%", nextText(schedule)
        );
    }

    private void run(CommandSender sender, String[] args) {
        if (!hasPermission(sender, "commanddelay.run")) {
            return;
        }

        if (args.length < 2) {
            messages().send(sender, "commands.run-usage");
            return;
        }

        String scheduleId = args[1];
        Optional<ScheduleDefinition> optionalSchedule = plugin.scheduleRepository().find(scheduleId);

        if (optionalSchedule.isEmpty()) {
            messages().send(sender, "commands.run-not-found", "%schedule%", scheduleId);
            return;
        }

        plugin.scheduleRunner().runNow(optionalSchedule.get());
        messages().send(
                sender,
                "commands.run-started",
                "%schedule%", optionalSchedule.get().id(),
                "%schedule_display_name%", TextFormatter.legacy(optionalSchedule.get().displayName())
        );
    }

    private void next(CommandSender sender) {
        if (!hasPermission(sender, "commanddelay.next")) {
            return;
        }

        PluginSettings settings = plugin.configManager().loadSettings();
        List<NextSchedule> nextSchedules = plugin.scheduleRepository().all().stream()
                .filter(ScheduleDefinition::enabled)
                .map(schedule -> new NextSchedule(schedule, schedule.nextExecution(settings.zoneId())))
                .filter(nextSchedule -> nextSchedule.dateTime().isPresent())
                .sorted(Comparator.comparing(nextSchedule -> nextSchedule.dateTime().get()))
                .toList();

        if (nextSchedules.isEmpty()) {
            messages().send(sender, "commands.next-empty");
            return;
        }

        messages().send(sender, "commands.next-header");

        for (NextSchedule nextSchedule : nextSchedules) {
            messages().send(
                    sender,
                    "commands.next-entry",
                    "%schedule%", nextSchedule.schedule().id(),
                    "%schedule_display_name%", TextFormatter.legacy(nextSchedule.schedule().displayName()),
                    "%next%", formatDateTime(nextSchedule.dateTime().get())
            );
        }
    }

    private boolean hasPermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }

        messages().send(sender, "commands.no-permission");
        return false;
    }

    private String enabledText(ScheduleDefinition schedule) {
        return messages().legacy(schedule.enabled() ? "commands.enabled" : "commands.disabled");
    }

    private String nextText(ScheduleDefinition schedule) {
        PluginSettings settings = plugin.configManager().loadSettings();
        return schedule.nextExecution(settings.zoneId())
                .map(this::formatDateTime)
                .orElseGet(() -> messages().legacy("formats.never"));
    }

    private String formatDateTime(ZonedDateTime dateTime) {
        String pattern = messages().legacy("formats.next-date-time");

        try {
            return dateTime.format(DateTimeFormatter.ofPattern(pattern));
        } catch (IllegalArgumentException exception) {
            return dateTime.toString();
        }
    }

    private List<String> scheduleIds() {
        return plugin.scheduleRepository().all().stream()
                .map(ScheduleDefinition::id)
                .toList();
    }

    private List<String> filter(List<String> options, String input) {
        String normalizedInput = input.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();

        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(normalizedInput)) {
                result.add(option);
            }
        }

        return result;
    }

    private MessageService messages() {
        return plugin.messageService();
    }

    private record NextSchedule(ScheduleDefinition schedule, Optional<ZonedDateTime> dateTime) {
    }
}
