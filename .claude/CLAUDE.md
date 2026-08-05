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
- **Language:** Java, `sourceCompatibility = 21` (desktop compiles with `release 21`; Android module uses Java 17). Було 23 — знижено 2026-08-03, бо жодної мовної фічі понад 21 у коді немає, а 23 вимагав рідкісний JDK і ламав construo-бандли під Linux/macOS (вони несуть JRE 21). Не піднімати назад без реальної потреби.
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

> ⚠️ **Ніколи `./gradlew build`** — воно тягне модуль `:android`, якому потрібен
> Android SDK (його тут немає), і падає. Завжди конкретні задачі: `:lwjgl3:jar`,
> `:lwjgl3:packageWinX64` тощо. Конфігурація `:android` без SDK проходить, тому
> решта модулів збирається нормально.

### CI (`.github/workflows/build.yml`)

Кожен push збирає гру й дає завантажуваний файл. Три групи задач:

- **`jar`** — `:lwjgl3:jar`, найшвидший сигнал, що код компілюється.
- **`bundles`** — матриця з 4 нативних бандлів (`packageWinX64`, `packageLinuxX64`,
  `packageMacX64`, `packageMacM1`) з ОДНОГО Linux-раннера: construo сам качає JDK
  цільової платформи, тож крос-збірка працює (перевірено — Linux-бандл зібрано на
  Windows). `fail-fast: false`, щоб зламаний таргет не забирав решту.
- **`release`** — лише з `main`: перекладає преліз `latest` на свіжий коміт.
  Артефакти Actions живуть 30 днів і вимагають входу; реліз дає постійне публічне
  посилання.

Пастки, вже обійдені — не «спрощувати» назад:
- `main/gradlew` мусить лишатись **виконуваним у git** (`100755`). Був `100644`,
  і на Linux-раннері не запускався б; у workflow ще й `chmod` про всяк випадок.
- Кеш JDK для construo ключований по `main/lwjgl3/build.gradle` — там лежать
  `jdkUrl`, і без цього кожен прогін качав би ~760 МБ.
- Робоча тека всіх кроків — `main/`, а не корінь репозиторію.

Вихід збірки: `main/lwjgl3/build/libs/*.jar` і
`main/lwjgl3/build/construo/dist/<appName>-<таргет>.zip`.

### Скріншоти (`debug/`)

Єдиний спосіб для того, хто не сидить перед монітором, побачити, як гра
СПРАВДІ виглядає. До появи цього все про вигляд перевірялось реконструкціями
в PIL, а вони не відтворюють ні метрик шрифту libGDX, ні `NinePatch`, ні `Table`.

- **`F12`** — знімок у `~/LostBatalion/screenshots/` (глобально, поряд із `F11`;
  повний шлях друкується в лог тегом `SCREENSHOT`).
- Типова тека — **домашня, а не проєктна, і це важливо**: робоча тека процесу при
  запуску через Gradle — `main/assets/`, тож `Gdx.files.local` складав знімки прямо
  в асети, звідки їх забирав `generateAssetList` і пакував у jar. Тому в
  `Screenshot.resolve` стоїть `Gdx.files.external`. Не міняти назад на `local`.
- **Режим «запустись, знімись, закрийся»** — системні властивості, не налаштування:

```bash
java -Dlb.shotAt=2,5 -Dlb.autoMatch=true -Dlb.shotExit=true \
     -Dlb.shotDir=<куди> -jar "lwjgl3/build/libs/Lost Batalion-1.0.0.jar"
```

| властивість | що робить |
|---|---|
| `lb.shotAt=2,5` | секунди від старту; вмикає весь режим (без неї нічого не діє) |
| `lb.autoMatch=true` | одразу в матч повз меню — інакше знімок покаже лише головне меню |
| `lb.shotExit=true` | вийти після останнього знімка |
| `lb.shotDir` | тека (абсолютна або відносна); типово `screenshots/` |
| `lb.scenario` | id карти; типово перша з `ScenarioCatalog` |
| `lb.screen=scenario\|settings` | відкрити меню-екран одразу; те саме призначення, що й `autoMatch`, але для меню — інакше автознімок бачить лише головний екран |
| `lb.devCmd="..."` | сценарій дев-консолі на старті матчу, розділювач `;` (див. розділ про консоль) |

Дві пастки, вже обійдені в `Screenshot.capture`, — не «спрощувати» назад:
**flipY** (OpenGL віддає рядки знизу вгору) і **альфа з буфера приходить нульовою**,
тож канал забивається `0xFF` вручну — без цього PNG виходить цілком прозорим.
Знімається саме кадровий буфер, тобто з HUD, bloom і туманом війни.

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
- `Infantry` — hp100, dmg15, **range90**, defense3, stealth0.20, size10.
  Єдиний тип із РОЗКИДОМ: `hitChanceAt` дає 100% до 30 одиниць і лінійно падає
  до 35% на межі 90 (мушкет, а не гвинтівка). Промах їсть кулдаун
  (`consumeAttackCooldown`) і малює трасер повз ціль, але не завдає урону.
  Кидок робить `CombatManager.tryAttack` через `DeterministicRandom` — рівно
  один на постріл. Хто повертає `hitChanceAt == ONE` (усі інші), RNG **не
  смикає взагалі**: гілка залежить від класу юніта, тож однакова на всіх
  клієнтах. Було range40 — при огляді 520 це 13:1, і позиція не важила нічого.
- `Cavalry` — hp80, dmg26, range20 (впритул), defense2, speed40, cooldown 0.7 с,
  stealth0.10. Штовхає ціль (`knockbackForce`), не стріляє (`usesRangedFx=false`),
  єдина тремтить у бою (`combatShakeScale`).
- `Artillery` — hp180, defense5, size32. `damage=0`/`attackRange=0` on purpose:
  it does **not** use the normal attack path. Instead `CombatManager` drives it:
  aim (3s) → AoE strike (splash 45, dmg120, spread 18) → reload (8s), range 220.
  `manualTarget` (RMB on enemy) overrides auto-target while alive & in range.
  **splash 45 мусить лишатись більшим за 16** — це мінімальна відстань між
  юнітами (`UnitSeparation`, hitRadius 8+8). Колись тут стояло 15, і AoE-зброя
  за побудовою не діставала більш ніж одну ціль: разом із damage 50 і spread 24
  це давало 1.25 dps за 150 золота, тобто 80 секунд на одного піхотинця.
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

### Топографія (`screens/renderer/TopographyOverlay`)
Оверлей ярусів висот на всю карту, перемикається клавішею **T** (WASD зайняті
камерою, ALT — оверлеєм огляду). Потрібен тому, що рельєф дає ±30% захисту і
змінює огляд у 3.25 раза, а на самій карті яруси майже не розрізняються —
вся математика працювала внаслі́пу.

Одна текстура розміром з карту, побудована ЛІНИВО при першому вмиканні
(1440×1440 ≈ 58 мс), далі просто малюється поверх карти з `ALPHA 0.45`.
Гіпсометрична шкала: низини зелені, височини руді, річки сині.
**Перевертень застосований при побудові** (рядок 0 пікмапи = найбільший
світовий Y), тому малюється БЕЗ від'ємної висоти — інакше оверлей ліг би
дзеркально до місцевості, яку описує. Фільтр `Nearest`: межі ярусів це східці
в масці, і згладжування домалювало б кольори неіснуючих ярусів.

Не плутати з `TerrainIndicatorRenderer` — той показує ярус під ВИДІЛЕНИМ
юнітом («де я стою»), а це «куди мені йти».

### Visibility / fog (`visibility/`)
- `VisibilitySystem` — recomputes `visibleToPlayer` for every enemy each frame.
  `effectiveSight = sightRange × sightMod(observer terrain)`; stealth from forest
  (target-in-forest ×1.8, forest-on-LOS-path ×4.0 via ray-march sampling) and
  elevation; detected if `dist ≤ effectiveSight × (1 − stealth)`.
- `FogOfWarRenderer` — draws fog; ALT held shows cursor sight overlay.
- Combat & rendering both respect `visibleToPlayer` (can't attack/see hidden enemies).

### Точки захоплення (`capture/`)
- Зони — **авторські дані сценарію** (`CaptureZone` у `ScenarioCard`), а НЕ
  похідні від маски. Кожна — опуклий чотирикутник (4 вершини у світових
  пікселях) плюс назва («A», «B», «C»), яку показує HUD. `CaptureManager`
  просто перекладає список у `CapturePoint`.
- Раніше точки виводились із плям VILLAGE (`findClusterCenters` + злиття <135),
  а зоною було коло r=70. Форма села ні на що не впливала: межа могла лежати
  посеред річки, а хутір із двох купок доводилось зливати порогом відстані.
- `CapturePoint.contains` — знак векторного добутку по 4 ребрах, цілочисельно.
  Обхід вершин будь-який; точка рівно на ребрі зараховується всередину.
  Чотирикутник МУСИТЬ бути опуклим, інакше перевірка бреше.
- Одна сторона в зоні → +2/тік (12 с на захоплення), обидві → прогрес завмирає,
  порожньо → −1/тік назад. Чужу точку спершу треба обнулити, потім набрати свою.
- Це стан симуляції: цілі числа, окремий компонент checksum (`C_POINTS`) і блок
  у `SimulationSnapshot`. Самі вершини в знімок не їдуть — вони константи.
- `screens/renderer/CapturePointRenderer` малює чотирикутник кольором того, хто
  ТЯГНЕ (`holder ?: owner`), і **мигає**, поки `0 < progress < FULL`.
  Проходу `BloomEffect` тут БІЛЬШЕ НЕМАЄ: світіння робило зону найяскравішим
  об'єктом кадру, у рази більшим за юніта. Звичайний альфа-блендинг, колір НЕ
  premultiplied. Той самий колір і той самий період мигання, що в літер у HUD.

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

### Супротивник-бот (`ai/`)
- **Бот — це ПІР, а не код симуляції.** `sim.tick()` цілочисельний і входить у
  checksum; евристика всередині нього мусила б уся лягти на `Fixed` і потрапити
  в хеш. Натомість `CommandContext` і без того єдиний канал зміни стану, тож бот
  сидить іззовні й віддає ті самі накази, що й людина. Усередині себе він
  вільний — float, колекції, будь-що: реплікується його ВИХІД, а не думка.
  Наслідок задарма: мережевий матч проти бота колись запрацює без переробок.
- `LocalMatchTransport(BotPlayer)` → `getPlayerIds()` стає `{0,1}`. Повідомлення
  бота народжується РАЗОМ із флешем локального гравця і на той самий тік. Це
  несуча деталь: `MatchRunner` чекає наказів від КОЖНОГО учасника, і тік без
  повідомлення від бота зупиняє матч намертво. Прив'язка до чужого флешу дає
  рівно одне повідомлення на тік без власного лічильника.
- **Бот НЕ шле checksum** — він не окрема симуляція. `ChecksumLedger` порівнює
  лише коли зібрались усі (`ChecksumLedger:99-101`), тож відсутній хеш просто не
  запускає звірку; `prune` чистить за відсічкою тіку, тож і витоку немає.
- **Зазирання немає.** Видимість рахується окремо для кожної сторони
  (`VisibilitySystem.update` проходить обох спостерігачів), тож
  `Unit.isVisibleTo(me)` — готова й чесна відповідь. Обійти туман війни означало
  б знецінити механіку, на якій тримається гра.
- `TacticalBrain` параметризований `playerId` — сторона це параметр, а не
  властивість класу. Інакше неможливий єдиний чесний тест рівнів: бот проти бота.
- **Три частоти** й причина в них одна: наказ СКИДАЄ те, що юніт робив. Наказ
  руху, повторений щочверть секунди, обнуляє маршрут і лишає роту тупцювати.
  Тому бій — щоразу (і лише при ЗМІНІ цілі), розподіл по точках — раз на 2 с,
  покупки — раз на 1 с.
- **Нічия точка пріоритетніша за чужу.** Спершу було навпаки — і два боти в
  дзеркальному матчі впирались один в одного, тримали по точці, а третю не брав
  ніхто: 290:290 усі п'ять хвилин, матч вирішувала анігіляція.
- **Збірний пункт і поріг маси — найважливіше в поведінці.** Підкріплення йде
  НЕ на ціль, а в тил (`RALLY_FRACTION` шляху від свого кута), і армія рушає,
  лише зібравши `massThreshold` бійців. Без цього при одній точці прибуток дає
  одного піхотинця на ~50 с, а йти йому 4–20 с — бот приходив по одному проти
  цілого загону й танув, скільки б не купував. Після виправлення важкий рівень
  тримає 8–10 юнітів усю партію замість 1–5.
- **Відкат «іду → збираюсь» міряється ВИСНАЖЕННЯМ, а не купністю.** Рота на
  марші розтягується в колону сама собою; бот, який дивився на купність,
  розвертався на півдорозі збиратись, потім знову вперед — і так усю гру.
  Виміряно: у такому стані HARD програвав EASY.
- **Розділення сил вимкнене за результатом вимірювання** — з ним HARD програв
  усі чотири матчі, без нього виграв три з чотирьох, за решти однакових
  налаштувань. Кіннота (26 урону проти 15 у піхоти) це головна ударна сила, і
  забирати її з кулака заради порожньої точки — програш головного бою. Не
  вмикати «бо розумно виглядає».
- `Difficulty` міняє ЛИШЕ якість рішень (період думання, резерв золота, поріг
  переваги для атаки, наявність гармат, чи читає рельєф). Ніяких бонусів до
  золота, урону чи огляду: перемога над ботом, який бачить крізь туман, нічого
  не означає. Вибір — `ScenarioScreen`, зберігається ІМЕНЕМ константи
  (`Settings.getBotDifficulty`), не ordinal: вставлений між наявними рівень тихо
  перемкнув би збережений вибір.
- `multiplayer` у `GameScreen` тепер = `transport != null`, а НЕ
  `playerIds.length > 1`: з ботом учасників теж двоє, але матч локальний, і
  паузу треба спиняти по-справжньому.
- **Пастка, вже набита:** `lastDecisionTick` не можна ініціалізувати
  `Integer.MIN_VALUE` — різниця `executeTick - lastDecisionTick` переповнюється
  в мінус, умова не спрацьовує ніколи, і бот мовчить увесь матч без єдиної
  ознаки поламаності.

### Дев-консоль (`debug/DevConsole`, `debug/DevView`)
Тильда (`~`) в матчі. **Створюється ТІЛЬКИ в одиночній грі** (`!multiplayer`):
майже все, що вона вміє, — пряма зміна стану повз накази або зміна темпу
годинника, і те, і те в lockstep розводить клієнтів на першому ж тіку.

- `aivsai [рівень] [рівень]` — обидві сторони під ботом. Окремого каналу не
  треба: `MatchRunner.issue` штампує накази ЛОКАЛЬНИМ playerId, тож мозок, чиї
  накази туди подають, стає гравцем за цим комп'ютером. Той самий прийом, яким
  бот тестується проти бота.
- `reveal on|off` — бачити всіх. **Тільки рендер.** Гейти видимості стоять у двох
  різних місцях: рендер (`UnitRenderer`, `Minimap`, `TerrainIndicatorRenderer` —
  усі через `DevView.visible`) і симуляція (`CombatManager`, `StateChecksum` —
  через `Unit.isVisibleTo` напряму). Чіпати можна ТІЛЬКИ перші, інакше
  спостереження змінює сам матч і побачене буде про іншу гру.
- `ai on|off` — наміри ботів на карті (`screens/renderer/AiIntentRenderer`):
  ціль, збірний пункт, суцільна лінія = йде / пунктир = збирається, підпис
  «рівень · стан · купа N/поріг · ціль». Без цього видно, ЩО бот робить, і не
  видно ЧОМУ — а всі знайдені в ньому вади були саме про «чому».
- `speed <0.25..8>` — множить лише `delta` для `runner.update`; крок тіку
  лишається 25 мс, тобто симуляція не міняється, просто тіків за секунду більше.
- `win <p>` реалізовано через знищення армії суперника, а НЕ через запис у
  `VictoryTracker`: так спрацьовує та сама умова, що в справжньому матчі, разом
  із підсумковими числами. Прямий запис показав би екран, якого гра не вміє
  виробляти.
- `spawn` спершу повертає ціну через `Economy.refund`, потім кличе
  `sim.spawnUnit` — той списує золото, і без цього консольний спавн тихо їв би
  скарбницю.
- `-Dlb.devCmd="aivsai hard hard;reveal on;ai on;speed 4;console on"` — сценарій
  на старті, розділювач `;`. Без нього консоль неможливо ні зняти автознімком,
  ні прогнати без людини за клавіатурою.
- `DevView` — статичні поля, і це свідомо: читачі це окремі рендерери, створені
  в різних місцях; тягнути посилання через п'ять конструкторів заради двох
  прапорців було б гірше. `GameScreen.dispose` кличе `DevView.reset()` —
  інакше ввімкнений `reveal` пережив би вихід у меню.

### Умова перемоги (`sim/VictoryTracker`)
- Дві умови. **Очки**: раз на 5 секунд (`SCORE_PERIOD_TICKS`) кожна сторона
  отримує `POINTS_PER_CAPTURE` (5) за кожну утримувану точку — та сама
  швидкість «очко за точку за секунду», але одним видимим кроком, а не
  цоканням секундоміра; хто перший набрав `TARGET` (900) —
  виграв. На 3 точках це 5 хв при повному контролі, 15 хв при одній точці.
  **Анігіляція**: сторона програє, коли в неї НЕМАЄ ні живих юнітів, ні
  замовлень у черзі, ні золота на найдешевшого юніта (ціна береться з
  `UnitType`, не константою). Усі три умови разом — по одній кожна дає хибну
  поразку, поки рота ще виходить із кута.
- Обидві перевірки — раз на період, не щотіку: анігіляція проходить по всіх
  юнітах. Викликається ОСТАННІМ у `GameSimulation.tick()`, після точок і
  економіки.
- **Симуляція після перемоги НЕ зупиняється.** У lockstep розбіжність у тому,
  чи виконався тік, — миттєвий десинк. Результат лише фіксується, показує
  його і глушить ввід виключно `GameScreen`.
- Нічия можлива: обидва перетнули `TARGET` на одному нарахуванні з рівним
  рахунком, або обидва анігільовані. `isDraw()` = `finished && winner == null`.
- Стан симуляції: checksum-компонент `C_VICTORY` і блок у `SimulationSnapshot`.
- UI: рахунок — `scoreSelfLabel`/`scoreFoeLabel` у `GameScreen`, ВЕРХ ПО ЦЕНТРУ
  `hudStage` впритул до межі екрана (`padTop 2`). Обидва числа ЗОЛОТІ
  (`COLOR_ACCENT`); свої перші. Синьо-червоні цифри пробували 2026-08-04 і
  відкинули — вибивались зі спокійної золотої гами HUD. Кольори сторін
  (`UIFactory.COLOR_TEAM_SELF/FOE`) лишились там, де справді розрізняють:
  на літерах точок і на зонах. Під числами ряд літер (`pointTags`): колір = хто тягне,
  сіра = нічия, мигання = зараз захоплюють. Літера НЕ рухається ніколи —
  групування «свої ліворуч, чужі праворуч» відкинуте саме тому, що змусило б
  її стрибати в мить захоплення. Там же тягнеться `waitLabel`, тому рознесені
  по `padTop` (2 проти 66) — інакше підказка лягає на цифри. У `CommandPanel`
  рахунку НЕМАЄ навмисно: та панель про золото й замовлення.
- **Підсумок матчу** знімається ОДИН раз, у `finish()`: `lost[2]` (втрачено
  юнітів) і `ownedAtEnd[2]` (утримувано точок). Знищено = втрати суперника, у
  1v1 окремого поля не треба. Тривалість — із `decidedTick`.
  Лічильника, що тікає на кожній смерті, тут НЕМАЄ навмисно: місць загибелі
  три (`takeDamage`, `takeDamageWithTerrain`, `GameSimulation.removeArmy`), і
  кожне нове стало б четвертим, про яке забули. Скан працює тому, що `allUnits`
  під час матчу НЕ чиститься (мертві лишаються з `alive == false`), а знімок
  пише всіх юнітів без фільтра — тож і ресинк історію втрат не з'їдає.
  `ownedAtEnd` саме ЗБЕРІГАЄТЬСЯ, а не читається під час показу: симуляція
  після перемоги не спиняється, і живий `countOwned` показував би, як підсумок
  повзе вже після кінця матчу.

### Екран результату (`screens/game/MatchResultOverlay`)
- **Вигляд і тайминги описані в [`DESIGN.md`](DESIGN.md) §9** — читай перед
  будь-якою правкою. Там же єдиний у грі дозвіл на помітну анімацію і межі
  того, що взагалі можна анімувати всередині `Table`.
- Смуга на всю ширину поверх затемненого поля, а НЕ панель: поле бою лишається
  видимим над і під нею.
- Показується через **1.2 с** після `isFinished()` (`GameScreen.RESULT_DELAY`),
  але ввід глушиться НЕГАЙНО (`GameScreen.inputSealed()` — ширше за
  `modalActive()` рівно на цю паузу). Ловець вводу мусить ПОГЛИНАТИ подію:
  порожня `modalStage` повертає `false`, і наказ провалився б далі по
  мультиплексору, поки вікна ще немає.
- `MatchNoticeOverlay` лишився ТІЛЬКИ обриву зв'язку. Раніше він показував і
  результат («стан однаковий»), але обрив — аварія, а кінець матчу — подія,
  заради якої гравець грав, і одного тону на двох замало.
- Вихід суперника теж іде сюди (`isAlone()` спрацьовує швидше за анігіляцію),
  але з `statsKnown = false`: `VictoryTracker` ще не знімав підсумок, і нулі
  замість чисел були б брехнею.
- В інтерфейсі — **«стратегічні точки», ніколи «села»**: село це те, як точка
  намальована на Жовтих Водах, а не те, чим вона буде на наступній карті.

### Масштабування інтерфейсу (`ui/UIScale`, `ui/ScreenResolution`)
- `UIScale.forHeight(h)` = `clamp(snap(h/720, 0.25), 0.75, 2.0)` — ОДИН множник на
  весь HUD. Стеля 2.0× — це запас різкості шрифту: `UIFactory.generateTintableFont`
  пече гліфи вдвічі більшими й малює з `setScale(0.5f)`.
- Три сцени матчу (`hudStage`, `pauseStage`, `modalStage`) — `ScreenViewport` із
  `setUnitsPerPixel(1/scale)`. Ставити множник ТРЕБА до `viewport.update(...)`.
- **HUD живе в логічних одиницях, ввід — у фізичних пікселях.** Усе, що перевіряє
  влучання по намальованому вручну HUD (`Minimap`, `SelectionPanel`), зобов'язане
  пройти через `UIScale.inputXToLogical` / `inputYToLogical`. Голого
  `Gdx.graphics.getHeight() - sy` в `GameScreen` більше немає — не повертати.
- `GameScreen.layoutHud()` рахує розкладку на початку кадру: мінікарта задає
  `frameWidth()`, під нього тиснеться `SelectionPanel.layout(...)`. Малювання й
  перевірка влучання читають ті самі числа.
- **Площа видимого світу стала.** `camera.zoom = userZoom × aspectFit()`, де
  `aspectFit = sqrt(900×580 / (worldW×worldH))`. У `camera.zoom` НЕ пишуть напряму —
  тільки `setZoom` (ефективний масштаб) або `setUserZoom`. Без цього гравець на
  21:9 бачив на 34% більше карти, ніж гравець у вікні.
- **Камера обмежується по ЦЕНТРУ** (`clamp(position, 0, mapWidth/Height)` у
  `handleCameraMovement`), тобто їй свідомо дозволено визирати за межі карти —
  збоку видно чорноту. Клемп по КРАЮ (`clamp(x, halfW, mapWidth − halfW)`)
  пробували 2026-08-04 і **відкинули**: він прибирає чорноту, але не пускає
  погляд за периметр узагалі, і керування стає тісним. Не повертати без
  прямого прохання. Саме тому `CommandPanel` має власну чорну плашку — вона
  лежить на межі кадру й не може розраховувати на карту під собою.
- Вікно нерозтяжне (`setResizable(false)`); розмір міняє лише список пресетів у
  налаштуваннях. Роздільність і повний екран лежать у `LostBatalion.Settings`,
  відновлює їх `LostBatalion.create()` — у лаунчері `Preferences` ще не існує.
- Зміна режиму з обробника scene2d — ТІЛЬКИ через `Gdx.app.postRunnable`:
  `BaseScreen.resize` перескладає сцену, тобто звільняє кнопку, всередині
  обробника якої ти стоїш.

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
- **Enemy AI Є** (`ai/`, з 2026-08-05) — бот замовляє війська, бере точки,
  б'ється, читає рельєф, має три рівні. Чого в нього ще немає: розділення сил на
  кілька напрямків (уся армія йде на одну ціль плюс гарнізони), відступу
  пошкоджених, засідок у лісі й будь-якої реакції на склад армії суперника.
- **Win/lose conditions Є** (`sim/VictoryTracker`, див. вище) — очки за точки
  плюс анігіляція. Інших цілей і сценарних завдань немає.
- **Pathfinding Є** (`path/NavGrid` + `path/PathFinder`, A* цілочисельний і
  детермінований), але ТІЛЬКИ по статичній місцевості: юніти в сітці не
  враховані, локального уникнення зіткнень і рознесення груп немає.
- **No tests** у репозиторії (`src/test` відсутній у всіх модулях). Прогонні
  харнеси пишуться в scratchpad сесії й не зберігаються.
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
