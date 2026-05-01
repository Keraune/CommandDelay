package dev.keraune.commanddelay;

import dev.keraune.commanddelay.command.CommandDelayCommand;
import dev.keraune.commanddelay.config.ConfigManager;
import dev.keraune.commanddelay.config.MessageService;
import dev.keraune.commanddelay.config.PluginSettings;
import dev.keraune.commanddelay.data.ExecutionHistory;
import dev.keraune.commanddelay.scheduler.ScheduleRepository;
import dev.keraune.commanddelay.scheduler.ScheduleRunner;
import dev.keraune.commanddelay.util.TextFormatter;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Clase principal del plugin.
 *
 * <p>Responsabilidad técnica:</p>
 * <ul>
 *     <li>Inicializar servicios principales.</li>
 *     <li>Registrar comandos.</li>
 *     <li>Controlar el ciclo de vida del scheduler.</li>
 * </ul>
 */
public final class CommandDelay extends JavaPlugin {

    private ConfigManager configManager;
    private MessageService messageService;
    private ExecutionHistory executionHistory;
    private ScheduleRepository scheduleRepository;
    private ScheduleRunner scheduleRunner;

    @Override
    public void onEnable() {
        createDefaultFiles();

        this.messageService = new MessageService(this);
        this.configManager = new ConfigManager(this, messageService);
        this.executionHistory = new ExecutionHistory(this, messageService);
        this.scheduleRepository = new ScheduleRepository(this, messageService);
        this.scheduleRunner = new ScheduleRunner(this, messageService, scheduleRepository, executionHistory);

        reloadPlugin(false);
        registerCommands();

        getLogger().info(messageService.plain("logs.plugin-enabled"));
    }

    @Override
    public void onDisable() {
        if (scheduleRunner != null) {
            scheduleRunner.stop();
        }

        if (executionHistory != null) {
            executionHistory.save();
        }

        if (messageService != null) {
            getLogger().info(messageService.plain("logs.plugin-disabled"));
        }
    }

    /**
     * Recarga todos los servicios dependientes de archivos YAML.
     *
     * @param notifyConsole si debe registrarse el proceso en consola.
     */
    public void reloadPlugin(boolean notifyConsole) {
        if (notifyConsole) {
            getLogger().info(messageService.plain("logs.reload-started"));
        }

        messageService.reload();
        configManager.reload();
        executionHistory.reload();
        scheduleRepository.reload();

        PluginSettings settings = configManager.loadSettings();
        TextFormatter.init(settings.useMiniMessage());
        scheduleRunner.start(settings);
        executionHistory.prune(settings.historyRetentionDays(), settings.zoneId());

        if (notifyConsole) {
            getLogger().info(messageService.plain(
                    "logs.reload-finished",
                    "%total%", String.valueOf(scheduleRepository.size())
            ));
        }
    }

    public ConfigManager configManager() {
        return configManager;
    }

    public MessageService messageService() {
        return messageService;
    }

    public ScheduleRepository scheduleRepository() {
        return scheduleRepository;
    }

    public ScheduleRunner scheduleRunner() {
        return scheduleRunner;
    }

    private void createDefaultFiles() {
        saveDefaultConfig();
        saveResourceIfMissing("messages.yml");
    }

    private void saveResourceIfMissing(String resourceName) {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        if (!new java.io.File(getDataFolder(), resourceName).exists()) {
            saveResource(resourceName, false);
        }
    }

    private void registerCommands() {
        PluginCommand command = getCommand("commanddelay");

        if (command == null) {
            return;
        }

        CommandDelayCommand commandExecutor = new CommandDelayCommand(this);
        command.setExecutor(commandExecutor);
        command.setTabCompleter(commandExecutor);
    }
}
