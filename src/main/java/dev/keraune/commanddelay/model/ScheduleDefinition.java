package dev.keraune.commanddelay.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Modelo inmutable de un horario configurado.
 *
 * <p>Este modelo no lee YAML ni ejecuta acciones. Solo representa datos ya validados
 * para mantener una separación clara entre configuración, lógica y ejecución.</p>
 */
public final class ScheduleDefinition {

    private final String id;
    private final String displayName;
    private final boolean enabled;
    private final boolean everyDay;
    private final Set<DayOfWeek> days;
    private final List<LocalTime> times;
    private final List<ScheduledAction> actions;
    private final ActionDelayMode actionDelayMode;
    private final ScheduleRequirements requirements;
    private final List<String> displayDays;
    private final List<String> displayTimes;

    public ScheduleDefinition(
            String id,
            String displayName,
            boolean enabled,
            boolean everyDay,
            Set<DayOfWeek> days,
            List<LocalTime> times,
            List<ScheduledAction> actions,
            ActionDelayMode actionDelayMode,
            ScheduleRequirements requirements,
            List<String> displayDays,
            List<String> displayTimes
    ) {
        this.id = id;
        this.displayName = displayName == null || displayName.isBlank() ? id : displayName;
        this.enabled = enabled;
        this.everyDay = everyDay;
        this.days = Set.copyOf(days);
        this.times = times.stream().sorted().toList();
        this.actions = List.copyOf(actions);
        this.actionDelayMode = actionDelayMode == null ? ActionDelayMode.ABSOLUTE : actionDelayMode;
        this.requirements = requirements == null ? ScheduleRequirements.empty() : requirements;
        this.displayDays = List.copyOf(displayDays);
        this.displayTimes = List.copyOf(displayTimes);
    }

    public String id() {
        return id;
    }

    /**
     * Nombre visual configurable del horario.
     *
     * <p>Puede contener formatos de color como &, hex y MiniMessage. El ID sigue
     * siendo el identificador técnico usado para configuración y comandos.</p>
     */
    public String displayName() {
        return displayName;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean everyDay() {
        return everyDay;
    }

    public Set<DayOfWeek> days() {
        return days;
    }

    public List<LocalTime> times() {
        return times;
    }

    public List<ScheduledAction> actions() {
        return actions;
    }

    /**
     * Alias de compatibilidad para textos/comandos antiguos que hablaban de comandos.
     */
    public List<ScheduledAction> commands() {
        return actions;
    }

    public ActionDelayMode actionDelayMode() {
        return actionDelayMode;
    }

    public ScheduleRequirements requirements() {
        return requirements;
    }

    public List<String> displayDays() {
        return displayDays;
    }

    public List<String> displayTimes() {
        return displayTimes;
    }

    public boolean canRunOn(DayOfWeek dayOfWeek) {
        return everyDay || days.contains(dayOfWeek);
    }

    /**
     * Calcula la próxima ejecución futura del horario usando la zona horaria configurada.
     */
    public Optional<ZonedDateTime> nextExecution(ZoneId zoneId) {
        ZonedDateTime now = ZonedDateTime.now(zoneId);

        return java.util.stream.IntStream.rangeClosed(0, 8)
                .mapToObj(offset -> now.toLocalDate().plusDays(offset))
                .filter(date -> canRunOn(date.getDayOfWeek()))
                .flatMap(date -> times.stream().map(time -> asZonedDateTime(date, time, zoneId)))
                .filter(candidate -> candidate.isAfter(now))
                .min(Comparator.naturalOrder());
    }

    private ZonedDateTime asZonedDateTime(LocalDate date, LocalTime time, ZoneId zoneId) {
        return ZonedDateTime.of(date, time, zoneId);
    }
}
