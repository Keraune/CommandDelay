package dev.keraune.commanddelay.model;

/**
 * Representa un comando configurado dentro de un horario.
 *
 * @param command comando sin procesar, tal como fue definido en config.yml
 * @param delaySeconds segundos de espera antes de ejecutarlo
 */
public record ScheduledCommand(String command, int delaySeconds) {

    public long delayTicks() {
        return Math.max(0, delaySeconds) * 20L;
    }
}
