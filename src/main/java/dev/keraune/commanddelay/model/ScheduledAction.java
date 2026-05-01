package dev.keraune.commanddelay.model;

import java.util.List;

/**
 * Acción programada dentro de un horario.
 *
 * <p>Una acción puede ser un comando de consola, un anuncio global en chat,
 * un title, un actionbar o un sonido. Mantenerlo como modelo inmutable facilita
 * validar el YAML una sola vez durante el reload.</p>
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
        float pitch
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
                1.0F
        );
    }

    public long delayTicks() {
        return Math.max(0, delaySeconds) * 20L;
    }
}
