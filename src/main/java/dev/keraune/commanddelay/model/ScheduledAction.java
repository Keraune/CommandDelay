package dev.keraune.commanddelay.model;

import java.util.List;

/**
 * Scheduled action inside a configured schedule.
 *
 * <p>An action can dispatch a console command, send chat output, show a title,
 * send an action bar, play a sound or display a boss bar. Keeping this model
 * immutable allows YAML validation to happen once during reload.</p>
 */
public record ScheduledAction(
        ActionType type,
        int delaySeconds,
        String command,
        List<String> messages,
        String message,
        String title,
        String subtitle,
        int fadeInTicks,
        int stayTicks,
        int fadeOutTicks,
        String sound,
        float volume,
        float pitch,
        BossBarSettings bossBar
) {

    public static ScheduledAction command(String command, int delaySeconds) {
        return new ScheduledAction(
                ActionType.COMMAND,
                Math.max(0, delaySeconds),
                command,
                List.of(),
                "",
                "",
                "",
                10,
                70,
                20,
                "",
                1.0F,
                1.0F,
                null
        );
    }

    public long delayTicks() {
        return Math.max(0, delaySeconds) * 20L;
    }
}
