# CommandDelay

**CommandDelay** es un plugin para servidores **Paper 1.21+** creado para ejecutar comandos y anuncios programados en horarios fijos.

El objetivo principal del plugin es servir como un sistema externo y configurable para eventos automáticos, especialmente para servidores que usan **MythicMobs** y necesitan spawnear bosses en horarios específicos sin depender completamente de los spawners internos del plugin.

---

## Características principales

- Ejecución de comandos en horarios fijos.
- Soporte para múltiples días y múltiples horas por evento.
- Sistema de acciones programadas con delay.
- Anuncios globales personalizados.
- Soporte para mensajes en:
  - Chat
  - Title
  - Actionbar
  - Sound
  - Bossbar
  - Command
- Soporte para PlaceholderAPI.
- Soporte para formatos de colores:
  - `&a`
  - `&l`
  - `&#FFAA00`
  - `<#FFAA00>`
  - `<red>`
  - `<bold>`
  - `<gradient:#FFAA00:#FF3300>Texto</gradient>`
- Sistema anti-duplicado para evitar ejecuciones repetidas en el mismo minuto.
- Bloqueo de horarios activos para evitar que un mismo evento se solape.
- Configuración separada para mensajes.
- Comandos administrativos con autocompletado.
- Compatible con ejecución manual de horarios.
- Soporte para requisitos:
  - mínimo de jugadores conectados
  - mundos permitidos
  - permiso requerido

---

## Requisitos

- Java 21
- Paper 1.21+
- Gradle 9.x recomendado
- PlaceholderAPI opcional

PlaceholderAPI no es obligatorio, pero si está instalado, CommandDelay podrá procesar placeholders en mensajes y comandos.

---

## Instalación

1. Compila el plugin:

```bash
./gradlew clean build
```

2. Copia el `.jar` generado desde:

```txt
build/libs/
```

3. Pega el `.jar` en la carpeta:

```txt
plugins/
```

4. Inicia o reinicia el servidor.

5. Edita los archivos generados en:

```txt
plugins/CommandDelay/
```

6. Recarga el plugin:

```txt
/commanddelay reload
```

También puedes usar:

```txt
/cdelay reload
```

---

## Comandos

| Comando | Descripción |
|---|---|
| `/commanddelay help` | Muestra la ayuda del plugin |
| `/commanddelay reload` | Recarga la configuración |
| `/commanddelay list` | Lista los horarios configurados |
| `/commanddelay info <id>` | Muestra información de un horario |
| `/commanddelay run <id>` | Ejecuta manualmente un horario |
| `/commanddelay next` | Muestra próximas ejecuciones |

Aliases:

```txt
/cdelay
/cmdelay
```

---

## Permisos

| Permiso | Descripción |
|---|---|
| `commanddelay.admin` | Permiso principal de administración |
| `commanddelay.reload` | Permite recargar el plugin |
| `commanddelay.list` | Permite listar horarios |
| `commanddelay.info` | Permite ver información de horarios |
| `commanddelay.run` | Permite ejecutar horarios manualmente |
| `commanddelay.next` | Permite ver próximas ejecuciones |

---

## Configuración general

Archivo:

```txt
config.yml
```

Ejemplo:

```yml
settings:
  timezone: "America/Lima"
  check-interval-seconds: 20
  remove-leading-slash: true
  translate-command-colors: true
  history-retention-days: 30
  debug: false
```

### Zona horaria

Para Perú:

```yml
timezone: "America/Lima"
```

Para Argentina:

```yml
timezone: "America/Argentina/Buenos_Aires"
```

---

## Ejemplo de evento de boss

```yml
schedules:
  boss_dragon_infernal:
    enabled: true
    display-name: "&#FF3300&lDragón Infernal"
    action-delay-mode: "SEQUENTIAL"
    days:
      - "TODOS"
    times:
      - "20:00"

    requirements:
      min-players-online: 1
      worlds: []
      permission: ""

    actions:
      - type: "CHAT"
        delay-seconds: 0
        messages:
          - ""
          - "<dark_gray>[<gradient:#FFAA00:#FF3300><bold>Boss</bold></gradient><dark_gray>] <yellow>El boss %schedule_display_name% <yellow>aparecerá en <white>5 minutos</white>."
          - "<gray>Prepárate para el combate."
          - ""

      - type: "TITLE"
        delay-seconds: 240
        title: "<red><bold>¡Boss próximo!</bold>"
        subtitle: "<yellow>Dragón Infernal aparecerá en 1 minuto"

      - type: "ACTIONBAR"
        delay-seconds: 30
        message: "<gold>El boss aparecerá en <red>30 segundos</red>..."

      - type: "BOSSBAR"
        delay-seconds: 0
        message: "<gradient:#FFAA00:#FF3300><bold>%schedule_plain_name%</bold></gradient> <yellow>aparece a las <white>%schedule_time_12%</white>"
        color: "PURPLE"
        overlay: "NOTCHED_10"
        progress: 1.0
        duration-seconds: 25
        decrease-progress: true
        flags: []

      - type: "SOUND"
        delay-seconds: 25
        sound: "entity.ender_dragon.growl"
        volume: 1.0
        pitch: 1.0

      - type: "COMMAND"
        delay-seconds: 5
        command: "mm mobs spawn DragonInfernal 1 world,100,70,100"

      - type: "CHAT"
        delay-seconds: 0
        messages:
          - ""
          - "<dark_gray>[<red><bold>Boss</bold></red><dark_gray>] <red>¡El Dragón Infernal ha aparecido!"
          - ""
```

---

## Modo de delay de acciones

Cada schedule puede decidir cómo interpretar `delay-seconds` usando `action-delay-mode`.

### ABSOLUTE

Es el modo clásico y se mantiene como comportamiento por defecto. Cada `delay-seconds` se cuenta desde el inicio del schedule.

```yml
schedules:
  boss_absolute:
    action-delay-mode: "ABSOLUTE"
    actions:
      - type: "CHAT"
        delay-seconds: 0
      - type: "TITLE"
        delay-seconds: 240
      - type: "COMMAND"
        delay-seconds: 300
```

### SEQUENTIAL

Ejecuta las acciones una por una. Cada `delay-seconds` se cuenta desde la acción anterior, por lo que ya no necesitas sumar manualmente los segundos acumulados.

```yml
schedules:
  boss_sequential:
    action-delay-mode: "SEQUENTIAL"
    actions:
      - type: "BOSSBAR"
        delay-seconds: 0
        duration-seconds: 60
        message: "<red>Boss en camino</red>"
        color: "RED"
        overlay: "PROGRESS"

      - type: "COMMAND"
        delay-seconds: 60
        command: "mm mobs spawn DragonInfernal 1 world,100,70,100"

      - type: "CHAT"
        delay-seconds: 0
        messages:
          - "<red>¡El boss apareció!"
```

También se aceptan alias para el modo secuencial: `SEQUENTIAL`, `CHAINED`, `RELATIVE`, `ONE_BY_ONE`.

---

## Tipos de acciones

### COMMAND

Ejecuta un comando desde consola.

```yml
- type: "COMMAND"
  delay-seconds: 0
  command: "say Evento iniciado"
```

Los comandos pueden escribirse con `/` o sin `/`.

Recomendado:

```yml
command: "mm mobs spawn DragonInfernal 1 world,100,70,100"
```

---

### CHAT

Envía mensajes globales al chat.

```yml
- type: "CHAT"
  delay-seconds: 0
  messages:
    - ""
    - "<gold><bold>EVENTO BOSS</bold>"
    - "<yellow>El boss aparecerá pronto."
    - ""
```

Soporta múltiples líneas.

---

### TITLE

Muestra un título en pantalla.

```yml
- type: "TITLE"
  delay-seconds: 0
  title: "<red>¡Boss próximo!"
  subtitle: "<yellow>Aparecerá en 1 minuto"
```

---

### ACTIONBAR

Muestra un mensaje encima de la barra de acceso rápido.

```yml
- type: "ACTIONBAR"
  delay-seconds: 0
  message: "<yellow>El boss aparecerá en <red>30 segundos</red>"
```

---

### SOUND

Reproduce un sonido a los jugadores objetivo.

```yml
- type: "SOUND"
  delay-seconds: 0
  sound: "entity.ender_dragon.growl"
  volume: 1.0
  pitch: 1.0
```

---

### BOSSBAR

Muestra una bossbar temporal a los jugadores objetivo. El texto soporta los mismos formatos de color que chat, title y actionbar: legacy, hex, tags simples, gradientes, MiniMessage básico y PlaceholderAPI.

```yml
- type: "BOSSBAR"
  delay-seconds: 0
  message: "<gradient:#FFAA00:#FF3300><bold>Boss próximo</bold></gradient> <yellow>Aparece a las <white>%schedule_time_12%</white>"
  color: "PURPLE"
  overlay: "NOTCHED_10"
  progress: 1.0
  duration-seconds: 60
  decrease-progress: true
  flags:
    - "DARKEN_SCREEN"
```

Colores soportados:

```txt
PINK, BLUE, RED, GREEN, YELLOW, PURPLE, WHITE
```

Formatos/overlays soportados:

```txt
PROGRESS / SOLID
NOTCHED_6 / SEGMENTED_6
NOTCHED_10 / SEGMENTED_10
NOTCHED_12 / SEGMENTED_12
NOTCHED_20 / SEGMENTED_20
```

`progress` acepta valores de `0.0` a `1.0`. También puedes usar porcentajes de `0` a `100`.

`duration-seconds` define cuántos segundos permanece visible. También puedes usar `duration-ticks`, `stay-ticks` o `stayTicks`.

`decrease-progress` está activado por defecto. Cuando está en `true`, la barra baja visualmente desde el `progress` inicial hasta `0` durante la duración configurada.

Flags soportadas:

```txt
DARKEN_SCREEN
PLAY_BOSS_MUSIC
CREATE_WORLD_FOG
```

Puedes escribirlas como lista:

```yml
flags:
  - "DARKEN_SCREEN"
  - "PLAY_BOSS_MUSIC"
```

O como booleanos:

```yml
darken-screen: true
play-boss-music: true
create-world-fog: false
```

---

## Requisitos por evento

Cada horario puede tener requisitos.

```yml
requirements:
  min-players-online: 1
  worlds: []
  permission: ""
```

### min-players-online

Cantidad mínima de jugadores conectados para ejecutar el evento.

```yml
min-players-online: 5
```

Si hay menos jugadores, el evento no se ejecuta.

---

### worlds

Define en qué mundos se tomarán en cuenta los jugadores objetivo.

Para todos los mundos:

```yml
worlds: []
```

Para un mundo específico:

```yml
worlds:
  - "world"
```

---

### permission

Filtra a los jugadores que recibirán anuncios, títulos, actionbar y sonidos.

Sin permiso requerido:

```yml
permission: ""
```

Con permiso requerido:

```yml
permission: "boss.events.receive"
```

Ejemplo:

```yml
requirements:
  min-players-online: 1
  worlds: []
  permission: "boss.events.receive"
```

Solo los jugadores con ese permiso recibirán las acciones visuales o sonoras.

---

## PlaceholderAPI

CommandDelay soporta PlaceholderAPI si el plugin está instalado en el servidor.

Ejemplo en mensajes:

```yml
messages:
  - "<yellow>Hola <aqua>%player_name%</aqua>, el boss aparecerá pronto."
```

Ejemplo en comandos:

```yml
- type: "COMMAND"
  delay-seconds: 0
  command: "eco give %player_name% 100"
```

Si el comando usa placeholders de jugador, se ejecutará por cada jugador objetivo.

Ejemplo con tres jugadores conectados:

```txt
eco give Keraune 100
eco give Steve 100
eco give Alex 100
```

Si el comando no usa placeholders de jugador, se ejecutará una sola vez desde consola.

---

## Placeholders internos

CommandDelay también puede usar placeholders propios.

Algunos ejemplos:

```txt
%schedule%
%schedule_display_name%
%schedule_time_24%
%schedule_time_24_ampm%
%schedule_time_12%
%schedule_times_24%
%schedule_times_24_ampm%
%schedule_times_12%
%online%
%targets%
```

Ejemplo:

```yml
- type: "COMMAND"
  delay-seconds: 0
  command: "say Hay %online% jugadores conectados."
```

---


### Placeholders de horario estático

Estos placeholders muestran las horas configuradas dentro de `times`, no el contador dinámico hasta la siguiente acción. Son útiles para hologramas, menús, scoreboards o bossbars donde quieras mostrar algo como `18:30`, `18:30 PM` o `8:00 AM`.

Por horario, formato clásico:

```txt
%commanddelay_time_24_<id>%
%commanddelay_time_24_ampm_<id>%
%commanddelay_time_12_<id>%
%commanddelay_times_24_<id>%
%commanddelay_times_24_ampm_<id>%
%commanddelay_times_12_<id>%
```

Por horario, formato moderno:

```txt
%commanddelay_<id>_time_24%
%commanddelay_<id>_time_24_ampm%
%commanddelay_<id>_time_12%
%commanddelay_<id>_times_24%
%commanddelay_<id>_times_24_ampm%
%commanddelay_<id>_times_12%
```

Para el próximo horario global:

```txt
%commanddelay_next_schedule_time_24%
%commanddelay_next_schedule_time_24_ampm%
%commanddelay_next_schedule_time_12%
%commanddelay_next_schedule_times_24%
%commanddelay_next_schedule_times_24_ampm%
%commanddelay_next_schedule_times_12%
```

Ejemplo:

```yml
times:
  - "08:00"
  - "18:30"
  - "8:00 AM"
  - "06:30 PM"
```

El parser también acepta variantes comunes como `8:00`, `8:00 AM`, `06:30 PM` y `18:30 PM`. Internamente las horas se normalizan a `LocalTime`, por eso el formato de salida depende del placeholder usado.

Si un placeholder devuelve `N/A`, ejecuta `/commanddelay list`: si el horario no aparece cargado, normalmente hay una hora inválida o el schedule no tiene acciones/comandos válidos.

Los placeholders por horario son tolerantes con el ID. Por ejemplo, si tu schedule se llama `Boss_Dragon`, puedes usar variantes como `Boss_Dragon`, `boss-dragon`, `boss_dragon` o incluso `Dragon` cuando el `display-name` coincide.

Resultados posibles:

```txt
time_24: 08:00
time_24_ampm: 08:00 AM
time_12: 8:00 AM
times_24: 08:00, 18:30
times_24_ampm: 08:00 AM, 18:30 PM
times_12: 8:00 AM, 6:30 PM
```

---

## Formatos de colores

CommandDelay soporta distintos estilos de color.

### Legacy

```txt
&aTexto verde
&cTexto rojo
&lTexto en negrita
```

### Hex

```txt
&#FFAA00Texto naranja
<#FFAA00>Texto naranja
#FFAA00Texto naranja
```

### MiniMessage

```txt
<red>Texto rojo</red>
<bold>Texto en negrita</bold>
<gradient:#FFAA00:#FF3300>Texto con gradiente</gradient>
```

---

## Funcionamiento del scheduler

El plugin revisa los horarios cada cierto tiempo según:

```yml
check-interval-seconds: 20
```

Cuando la fecha, el día y la hora coinciden, CommandDelay ejecuta el horario.

Para evitar duplicados:

- Guarda un historial de ejecuciones.
- Marca cada horario por fecha y minuto.
- Bloquea horarios activos hasta que terminen todas sus acciones.
- Cancela tareas pendientes al recargar o apagar el plugin.

---

## Archivo de historial

CommandDelay genera un archivo interno para evitar duplicados:

```txt
last-executions.yml
```

No se recomienda editar este archivo manualmente.

---

## Mensajes del plugin

Los mensajes administrativos están en:

```txt
messages.yml
```

Desde ahí puedes editar textos, colores y prefijos sin modificar el código fuente.

---

## Uso recomendado con MythicMobs

CommandDelay puede usarse para spawnear bosses de MythicMobs en horarios exactos.

Ejemplo:

```yml
- type: "COMMAND"
  delay-seconds: 300
  command: "mm mobs spawn DragonInfernal 1 world,100,70,100"
```

También puedes agregar avisos antes del spawn:

```yml
- type: "CHAT"
  delay-seconds: 0
  messages:
    - "<yellow>El boss aparecerá en 5 minutos."

- type: "TITLE"
  delay-seconds: 240
  title: "<red>¡Boss próximo!"
  subtitle: "<yellow>Aparecerá en 1 minuto"

- type: "COMMAND"
  delay-seconds: 300
  command: "mm mobs spawn DragonInfernal 1 world,100,70,100"
```

---

## Compilación para desarrolladores

Clona el repositorio:

```bash
git clone https://github.com/tuusuario/CommandDelay.git
cd CommandDelay
```

Compila:

```bash
./gradlew clean build
```

Ejecuta servidor de prueba:

```bash
./gradlew runServer
```

---

## Tecnologías usadas

- Java 21
- Paper API 1.21+
- Gradle Kotlin DSL
- PlaceholderAPI
- Adventure API
- MiniMessage

---

## Estado del proyecto

CommandDelay está pensado como una solución simple, configurable y estable para programar eventos automáticos en servidores Paper.

Su uso principal es la ejecución de comandos y anuncios programados, especialmente para eventos de bosses con MythicMobs.
