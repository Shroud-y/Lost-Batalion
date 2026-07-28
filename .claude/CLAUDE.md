# Lost Batalion — CLAUDE.md

Real-time strategy (RTS) game built with **libGDX**. Player commands an army,
builds bases, gathers resources, develops tactics to beat an opponent. Emphasis
on terrain, positioning, line-of-sight and formations over raw army size.

> Design pillars (from the brief): strategic planning, reading the battlefield,
> reacting to change, weighing alternatives. Terrain and visibility are
> first-class mechanics, not decoration.

Most in-code comments and commit messages are in **Ukrainian**. Match that when
editing existing comments; new code comments may be English or Ukrainian.

---

## ⚠️ Repo layout gotcha

The Gradle project root is **`main/`**, NOT the repo root. The repo root
(`E:\GitReps\Lost-Batalion`) contains only `main/` plus IDE files.

- Run all Gradle commands from `main/`: `cd main && ./gradlew <task>`
- `settings.gradle`, `gradlew`, `assets/`, and all modules live under `main/`.

## Stack

- **Engine:** libGDX `1.14.0`
- **Language:** Java, `sourceCompatibility = 23` (desktop compiles with `release 23`; Android module uses Java 17)
- **Build:** Gradle (wrapper included), Android plugin `8.9.3`
- **Extra libGDX deps declared** (mostly unused so far): ashley `1.7.4` (ECS), gdx-ai `1.8.2`, gdx-box2d, gdx-freetype
- **Packaging:** construo `2.1.0` (native bundles + jlink per-OS); optional GraalVM native image (`enableGraalNative=false`)

> ⚠️ ashley (ECS) and gdx-box2d are on the classpath but the game does **not**
> use an entity-component-system or Box2D physics. Units are plain OOP classes
> (`Unit` hierarchy) with hand-rolled movement/combat. Don't assume ECS.

## Modules (`main/settings.gradle`: `lwjgl3, android, server, core`)

- **`core`** — all shared game logic. Where nearly all work happens.
- **`lwjgl3`** — desktop launcher (LWJGL3). Primary run target.
- **`android`** — Android launcher. Needs Android SDK.
- **`server`** — standalone stub, no access to `core`. Not wired into gameplay.

Base package: `io.jababa.lost_batalion`

## Run / build (from `main/`)

- `./gradlew lwjgl3:run` — launch desktop game
- `./gradlew lwjgl3:jar` — runnable jar → `lwjgl3/build/libs`
- `./gradlew build` — build everything
- `./gradlew server:run` — run server stub
- **No tests exist** (`src/test` absent). `./gradlew test` is a no-op.

---

## Architecture

`LostBatalion extends Game` (entrypoint) → `Screen` per state:
`MainMenuScreen` → `ScenarioScreen` → `GameScreen` (+ `SettingsScreen`).
`BaseScreen` is the shared scene2d-Stage base for menu screens.

Input: global `InputMultiplexer` in `LostBatalion`; each screen swaps processor 1
via `game.setScreenInputProcessor(...)`. F11 toggles fullscreen globally.

### GameScreen — the core loop
`core/.../screens/game/GameScreen.java` orchestrates everything: camera
(`OrthographicCamera` + `ExtendViewport` 900×580), map texture, and per-frame
`update`/`render` of all subsystems. It builds an `InputMultiplexer` that differs
for desktop (mouse/keyboard `buildGameInput`) vs mobile (`MobileTouchHandler` +
`GestureDetector`/`GameInputHandler`).

Map size = map texture size. World coords are pixel coords (Y-up); terrain masks
are sampled with Y flipped (`height-1-y`).

### Units (`units/`)
- `Unit` (abstract): hp/maxHp, speed, damage, attackRange, attackCooldown,
  `defense` (flat armor), `sightRange`, `stealthRating`, `visibleToPlayer`,
  position/target, `moving`, `selected`, `alive`. Linear `moveTo` movement (no
  pathfinding). `takeDamage` / `takeDamageWithTerrain` (armor then terrain
  multiplier). `attack` / `attackWithTerrain`.
- `Infantry` — hp100, dmg15, range40, defense3, stealth0.20, size10.
- `Artillery` — hp180, defense5, size32. `damage=0`/`attackRange=0` on purpose:
  it does **not** use the normal attack path. Instead `CombatManager` drives it:
  aim (3s) → AoE strike (splash 45, dmg120, spread 18) → reload (8s), range 220.
  `manualTarget` (RMB on enemy) overrides auto-target while alive & in range.
- `UnitManager` — owns `allUnits` + `selectedUnits`. Selection (click / shift /
  rect), formation moves (`moveSelectedTo` grid, `moveSelectedToLine`), dead-unit
  cleanup, per-frame update applying terrain speed multiplier.

### Combat (`units/CombatManager.java`)
Central combat brain, runs every frame. Handles:
- Auto-attack: each non-artillery unit attacks nearest **visible** enemy in range.
- Manual attack orders (`orderAttack`): forms a line facing the target
  (`AttackGroup` SPREAD→ADVANCE phase machine, `AttackOrder` per unit).
- Terrain-aware damage via `TerrainCombatModifier` (+ forest ×1.5 defense).
- Artillery special-cased separately (`updateArtillery`, `fireArtillery`,
  `applyArtilleryAoe` — AoE hits friendlies too, falloff by distance).
- Visual effects: `ShotEffect` pool (64), `ArtilleryStrikeEffect` (shell arc +
  `ExplosionAnimation`), `TargetPopupManager`, aim/reload bars, shot sound.

### Terrain (`terrain/`) — mask-driven
Two grayscale/colour PNG masks per scenario, sampled by pixel colour
(`TerrainMaskManager`, tolerance 20):
- **Forest mask** (`*_mask.png`): forest tiles (movement + LOS + stealth).
- **Terrain/topography mask** (`*_terrain_mask.png`): elevation + rivers.
- `TerrainType` enum: NONE, FOREST, RIVER, LOWLANDS/PRE_LOWLANDS (1a/1b),
  PLAINS/PLAINS_ALT (2a/2b), PRE_HIGHLANDS/HIGHLANDS (3a/3b), VILLAGE. RGB codes
  documented in `TerrainMaskManager`. VILLAGE (#F0FF00) лежить у масці **лісу**,
  як і річки, і на рух/бій не впливає — з нього будуються точки захоплення.
- `TerrainMovementModifier`: forest ×0.70, river ×0.40, hills ×0.85 (multiply).
- `TerrainCombatModifier`: defender defense multiplier by attacker-vs-defender
  elevation (high ground bonus, valley penalty). See table in that file.

### Visibility / fog (`visibility/`)
- `VisibilitySystem` — recomputes `visibleToPlayer` for every enemy each frame.
  `effectiveSight = sightRange × sightMod(observer terrain)`; stealth from forest
  (target-in-forest ×1.8, forest-on-LOS-path ×4.0 via ray-march sampling) and
  elevation; detected if `dist ≤ effectiveSight × (1 − stealth)`.
- `FogOfWarRenderer` — draws fog; ALT held shows cursor sight overlay.
- Combat & rendering both respect `visibleToPlayer` (can't attack/see hidden enemies).

### Точки захоплення (`capture/`)
- `CaptureManager` будує точки з плям VILLAGE у масці лісу
  (`TerrainMaskManager.findClusterCenters` → заливка по 4 сусідах), зливаючи
  плями, ближчі за 135 px, в одне село. На Жовтих Водах виходить 3 точки.
- Радіус кола 70 px. Захоплює той, хто зайшов у КОЛО, а не на пікселі села.
  Одна сторона в колі → +2/тік (12 с на захоплення), обидві → прогрес завмирає,
  порожньо → −1/тік назад. Чужу точку спершу треба обнулити, потім набрати свою.
- Це стан симуляції: цілі числа, окремий компонент checksum (`C_POINTS`) і блок
  у `SimulationSnapshot`.
- `screens/renderer/CapturePointRenderer` малює коло — біле, поки нейтральне,
  і сектором кольору сторони в міру захоплення. Йде окремим проходом
  `BloomEffect` ДО юнітів; колір premultiplied, бо буфер bloom саме такий.

### Економіка й поповнення (`economy/`)
- `Economy` — золото на сторону. Старт 100, +5 за кожну утримувану точку раз на
  5 с (`INCOME_PERIOD_TICKS`). Лічильник періоду один на матч.
- `UnitType` — каталог замовлюваного: `INFANTRY` (50), `ARTILLERY` (150),
  розділи `INF`/`ART`. **Порядок констант — частина протоколу**: `SpawnCommand`
  везе `ordinal()`.
- `SpawnQueue` / `PendingSpawn` — замовлення живе 2 с (`HOLD_TICKS`) як
  напівпрозорий силует, і його можна зняти з поверненням золота. Далі юніт
  виходить із кута свого гравця (хост — лівий нижній, гість — правий верхній,
  `GameSimulation.SPAWN_MARGIN`) і йде в задану точку **з pathfinding**.
- Команди `SpawnCommand` / `CancelSpawnCommand`. Золото списується на тіку
  ВИКОНАННЯ, не при кліку. Усе входить у checksum (`C_ECONOMY`) і в знімок.
- UI: `screens/ui/CommandPanel` (scene2d на `hudStage`) — прибуток, золото,
  кнопка «ВІЙСЬКА» і прозоре меню розділів; `screens/renderer/SpawnGhostRenderer`
  — привид під курсором (екранні координати) і на місці висадки (світові).
- Кнопки «МЕНЮ» в лівому верхньому куті БІЛЬШЕ НЕМАЄ — там економіка. Паузу
  відкриває ESC; на мобільному вона зараз недоступна.

### Formations / commands
- `FormationDragHandler` — RMB-drag to place a straight formation line.
- `CurvedFormationCommand` — freehand drawn curve; samples path, prevents
  self-intersection, distributes selected units along it. Toggled from `SelectionPanel`.

### UI (`ui/`, `screens/ui/`, `screens/effects/`)

> **Вигляд гри описаний у [`DESIGN.md`](DESIGN.md) — читай його перед будь-якою
> роботою над UI.** Палітра, типографіка, розкладка, тайминги руху й технічні
> пастки (запечений колір шрифту, розрідження літер, підміна станів кнопки,
> фільтрація). Еталон — головне меню; решта екранів на нього ще не переведена.

- `UIFactory` — programmatic scene2d styles/drawables (FreeType font `main.ttf`),
  `disposeAll()` lifecycle. `COLOR_ACCENT` is the theme accent.
- `SelectionPanel` — bottom-left slide-in panel: unit portraits + formation
  button (+ artillery button when artillery selected). Custom SpriteBatch draw,
  not scene2d.
- `PauseOverlay`, `ForestTooltip`, `TargetPopup`.
- `screens/effects/MoveMarker` — glowing cross at the move-order destination.
  Sprite `ui/movemarker.png` (procedural pixmap fallback), drawn twice with
  `glBlendFuncSeparate`: overlay pass + additive pass, weighted by the animation
  phase. Separate alpha factors because the PNG is straight-alpha while the
  bloom buffer is premultiplied.
- `screens/effects/BloomEffect` — generic FBO post-process (separable gaussian at
  half res, sharp layer + additive halo). `begin(viewport)` / `end()`; silently
  self-disables if the shader won't compile. Currently only wraps `MoveMarker`.

### Mobile (`mobile/`)
- `MobileTouchHandler` — long-press = box select, drag = pan camera, double-tap =
  move/attack, tap = select. `GameInputHandler` — pinch-zoom gestures.
- Desktop vs mobile chosen at runtime via `Gdx.app.getType()`.

## Assets (`main/assets/`)
`units/` (infantry player/enemy, artillery), `effects/` (explosion sheet, shell),
`ui/` (tooltips, tiles, icons), `scenarios/` (Zhovty Vody map + 2 masks),
`fonts/main.ttf`, `sounds/shot.wav`. `assets.txt` is auto-generated by the
`generateAssetList` Gradle task. Textures loaded via `Gdx.files.internal(...)`
with graceful fallbacks (procedural pixmaps) when a file is missing.

## Scenarios
Only one: **Zhovti Vody** (`ScenarioScreen.buildScenarios()`), marked
"Coming soon". A `ScenarioCard` bundles map texture + forest mask + terrain mask.
Add scenarios there.

---

## Current state / known gaps (as of this doc)

Implemented: unit selection & movement, formations (line + curved), terrain
movement/combat/visibility modifiers, fog of war, infantry auto/ordered combat,
artillery AoE strikes, mobile controls, main menu / scenario / settings screens,
pause overlay.

Not yet implemented (despite the design brief mentioning them):
- **No base building.** Економіка є (золото з точок захоплення + замовлення
  військ), але видобутку ресурсів і будівництва немає.
- **No enemy AI** — enemy units are static spawns; they only auto-fire when a
  player unit is in range. No strategic opponent.
- **No win/lose conditions**, no objectives, no HUD beyond selection panel.
- **No pathfinding** — units move in straight lines, no obstacle avoidance.
- **No tests.**
- Only one scenario/map. `server` module is an unused stub.
- Some debug `Gdx.app.log` calls in the combat/unit hot path (`Unit.update`,
  `CombatManager.tryAttack`) at LOG_DEBUG.

## Conventions / gotchas

- Dispose everything: subsystems own their `SpriteBatch`/`ShapeRenderer`/
  `Texture`/`Pixmap` and dispose in `dispose()`. Follow the pattern for new ones.
- Terrain masks sampled with **Y flipped**; world Y is up, pixmap Y is down.
- Artillery is deliberately excluded from the normal `attack()` / order pipeline
  — guard new combat code with `instanceof Artillery` checks like existing code.
- `visibleToPlayer` gates both rendering and targeting — respect it everywhere.
- Comments/log tags often Ukrainian (`COMBAT`, `UNIT`). Keep consistent.
