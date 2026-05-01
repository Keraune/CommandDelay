package dev.keraune.commanddelay.model;

import java.time.Duration;
import java.time.ZonedDateTime;

/**
 * Representa la siguiente acción calculada para un horario.
 *
 * <p>Este modelo se usa principalmente para PlaceholderAPI. Mantiene la información
 * mínima necesaria para que plugins externos, como hologramas o scoreboards,
 * puedan consultar cuánto falta para la próxima acción sin leer archivos YAML.</p>
 */
public record NextActionSnapshot(
        String scheduleId,
        ActionType actionType,
        ZonedDateTime dueAt,
        boolean active
) {

    /**
     * Devuelve el tiempo restante hasta la acción. Nunca retorna una duración negativa.
     */
    public Duration remaining(ZonedDateTime now) {
        Duration duration = Duration.between(now, dueAt);
        return duration.isNegative() ? Duration.ZERO : duration;
    }
}
