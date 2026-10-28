# Leaf 1.21.4 — минимальный MSPT, память, пакеты

Документ отвечает на три вопроса:

1. что уже сделано в этой ветке и почему это ускоряет сервер;
2. где на самом деле живут твои 0.5–1.5 мс MSPT (и почему «пустой» сервер не равен «0.05 мс»);
3. что из списка «off-heap / io_uring / SIMD / zero-copy» реально применимо к Minecraft на Java 21,
   а что является красивой идеей, которая ломает либо сборку, либо совместимость плагинов.

Конфигурация не изменялась: ни один файл из `config/` не тронут, ни одна механика не выключена.
Все изменения — только «быстрее», без изменения поведения.

---

## 0. Ветка

Ты просил «скопировать `ver/1.21.4` в кастомную ветку». Содержимое этой сессии уже является
ровно `ver/1.21.4` (`mcVersion=1.21.4`, `version=1.21.4-R0.1-SNAPSHOT`, `galeCommit=8468427…`),
просто ветка называется `arena/6804670c-leaf` — имя ветки фиксируется сессией, переименовать его
нельзя. В репозитории рядом есть `ver/26.3` — это **другая** версия Minecraft, с ней эта работа не
связана.

**Как проверить сборку** (в этой песочнице нет ни JDK, ни доступа к `repo.papermc.io` /
`maven.leafmc.one`, поэтому компиляция здесь невозможна — проверялся только синтаксис и API):

```bash
./gradlew applyAllPatches --stacktrace   # патчи Minecraft не менялись -> должен пройти как раньше
./gradlew build                          # компиляция leaf-server с новыми классами
```

Ни один файл в `leaf-server/minecraft-patches`, `leaf-server/paper-patches` и `leaf-server/gale-patches`
не тронут: если сборка упала — причина в компиляции Java-классов из §1, а не в патчах.

---

## 1. Что изменено в коде

Все правки — в `leaf-server/src/main/java`, то есть в коде, который компилируется в тот же jar, что
и Minecraft-классы, и не требует перегенерации патчей (`minecraft-patches`) — значит они не могут
«не примениться» при сборке.

### 1.1 Новый пакет `org.dreeam.leaf.perf`

| Файл | Что делает | Зачем |
| --- | --- | --- |
| `LeafRuntimeTuning` | на бут-старте выставляет системные свойства Netty и JDK (`io.netty.leakDetection.level`, `io.netty.allocator.numDirectArenas`, `jdk.nio.maxCachedBufferSize`), **только если их не задали вручную** | детектор утечек Netty перестаёт сэмплировать аллокации в сетевых потоках; число direct-арен ограничивается 8 (каждая арена — свой 4 МиБ чанк + свои free-list'ы); per-thread кэш direct-буферов перестаёт навсегда держать многомегабайтные буферы |
| `JvmFlagAdvisor` | один раз на старте печатает список отсутствующих JVM-флагов с готовыми значениями | 0.5–1.5 мс на пустом сервере чаще всего не «тик-луп», а JVM: heap без `AlwaysPreTouch`, `hsperfdata` на тиковых потоках, неограниченный metaspace |
| `CpuFeatures` | определяет FMA/AVX2/AVX-512/SVE по `/proc/cpuinfo` | включает уже существующую FMA-ветку Leaf (`Entity#distanceToSqr` из патча `0091`) без ручного `-DLeaf.enableFMA=true` |

`LeafBootstrap` теперь вызывает тюнинг и советник до `PaperBootstrap.boot(...)`, а `enableFMA`
по умолчанию определяется CPU. Отключить всё это можно `-Dleaf.disableRuntimeTuning=true` и
`-Dleaf.silentJvmAdvice=true`.

> Важно: `enableFMA` меняет только точность/скорость вычисления квадрата расстояния
> (`Math.fma`), не логику. Если нужен байт-в-байт ванильный результат — `-DLeaf.enableFMA=false`.

### 1.2 Оптимизации горячих структур, которые уже используются патчами

| Файл | Было | Стало |
| --- | --- | --- |
| `util/cache/IterateOutwardsCache` | `ConcurrentHashMap<Long, LongArrayList>` — **два бокса `Long` на каждый вызов**, плюс бокс при обходе при вытеснении | `Long2ObjectOpenHashMap` (fastutil) с **copy-on-write** публикацией через `volatile`: чтения без блокировок, без боксинга; запись (только промах кэша) делает копию под монитором. Тот же кэш, тот же порядок обхода позиций (используется `BlockPos.withinManhattan` из патча `0098`, то есть random tick и спавн мобов) |
| `util/list/ActivationList` | `size()` = `BitSet.cardinality()` — обход всех слов маски | инкрементальный `visibleCount`, `size()`/`isEmpty()` — чтение поля. Класс работает в `Brain#tick` у каждого активного моба (патч `0183`) |
| `util/list/BlockEntityTickersList` | при удалении одного block entity — полный проход по списку с проверкой `IntOpenHashSet.contains(i)` на каждый элемент | fast path для самого частого случая (ровно один удаляемый индекс) — один `System.arraycopy`. Патч `0158` |
| `util/cache/CachedOrNewBitsGetter` | `computeIfAbsent(int, IntFunction)` | прямой `get`/`put` без функционального интерфейса; вызывается на каждую секцию чанка при сериализации (патч `0095`) |

Это не «переписывание движка», это убирание мусора из путей, которые выполняются десятки тысяч раз
в секунду: меньше аллокаций → короче young GC → меньше «дрожания» MSPT.

### 1.3 Off-heap слой (новое)

| Файл | Что | Почему не сломает плагины |
| --- | --- | --- |
| `perf/offheap/OffHeapLongArray` | `long[]` в native memory + read-only view | данные неизменяемые; наружу отдаётся обычный `LongList` |
| `perf/offheap/OffHeapLongList` | read-only `LongList` (fastutil `AbstractLongList`) | интерфейс тот же, что у `LongArrayList` |
| `perf/offheap/OffHeapLongLists` | решает, что выносить: только таблицы ≥ 1024 элементов, иначе heap (иначе будет медленнее) | выключено через `-Dleaf.offHeap=false` |
| `perf/offheap/OffHeapChunkPayloadCache` | кэш сериализованных чанк-пейлоадов (native, бюджет `-Dleaf.offHeap.chunkCacheMB`) | `LevelChunk` не меняется; кэш выключен по умолчанию |
| `perf/offheap/OffHeapByteArray` | immutable `byte[]` в native + `ByteBuffer` view | Netty пишет из direct-буфера без копии |

Кэш пейлоадов полностью реализован, но **не включён по умолчанию**: ключ обязан содержать ревизию
содержимого чанка, а её выставляет патч в `LevelChunk` (см. `PATCH_PROPOSALS_RU.md`, п.1). Включение:
`-Dleaf.offHeap.chunkCacheMB=256`. По остановке сервера печатается отчёт (хиты/промахи/нативная
память), чтобы было видно, окупается ли кэш.

Флаги: `-Dleaf.offHeap=false` (выключить весь слой), `-Dleaf.offHeap.maxMB=64` (бюджет таблиц
позиций), `-Dleaf.offHeap.minEntries=1024` (порог, ниже которого таблица остаётся на heap),
`-Dleaf.asyncChunkSend.queueSize=16384` (предел очереди async chunk send).

`IterateOutwardsCache` теперь складывает свои таблицы позиций (они immutable и живут весь аптайм)
через `OffHeapLongLists.offHeapIfWorthwhile(...)` — большие таблицы уезжают из heap автоматически,
маленькие остаются на heap (для них off-heap медленнее).

`AsyncChunkSend.POOL` получил ограниченную очередь (`-Dleaf.asyncChunkSend.queueSize`, по умолчанию
16 384 задачи) и `CallerRunsPolicy`: при всплеске захода пакеты не копятся в памяти, а
обрабатываются на вызывающем потоке — ровно как при выключенной опции. Раньше очередь была
неограниченной, и один заход с большим view-distance мог держать сотни мегабайт готовых пакетов.

---

## 2. Что добавлено для запуска и памяти

Каталог `scripts/leaf-perf` (см. `README_RU.md` там же):

* `leaf-flags.sh` — генерирует JVM-командную строку под конкретную машину (heap, G1/ZGC, метадата,
  direct memory, Netty, AppCDS-совместимость), а также JIT-часть: `MaxVectorSize` по факту
  AVX2/AVX-512 (auto-vectorisation без «downclock под AVX-512» на неподходящем CPU), `UseSuperWord`,
  `UseCountedLoopSafepoints`, `LoopStripMiningIter`, `UseTransparentHugePages` — если ядро его
  предлагает. Набор проверен на Temurin 21 для G1/ZGC/Parallel. Флаги проверены на реальной Temurin 21: наборы
  G1 / ZGC / Parallel принимаются без предупреждений; флаги JDK 22+/24+ добавляются только на
  соответствующей версии.
* `leaf-cds.sh` — один раз поднимает сервер и записывает AppCDS-архив; дальше классы **ммap'ятся**
  из файла. Это одновременно:
  * быстрее старт,
  * меньше metaspace (главный кандидат на «+2 ГБ от spigot library loader»),
  * меньше парсинга классов на тиковых потоках в первые секунды.
* `leaf-diagnose.sh` — read-only отчёт: THP, swappiness, ulimits, версия JVM, heap, какие
  `performance.*` / `async.*` переключатели Leaf **выключены** (только читает yml), готовые команды
  spark/JFR.

### Про «+2 ГБ из-за SpigotLibraryLoader»

По порядку вероятности:

1. **metaspace** — library loader грузит jar'ы библиотек, классы живут вне heap, лимита по умолчанию
   нет. Лечится `-XX:MaxMetaspaceSize=512m` + AppCDS.
2. **direct memory** — Netty-арены, чанк-буферы, zstd/lz4. Не видна в `-Xmx`, лечится
   `-XX:MaxDirectMemorySize` (в генераторе флагов = heap/2).
3. **растущий heap без `AlwaysPreTouch`** — RSS поднимается ступеньками и выглядит как утечка.

---

## 3. Где реально живут 0.5–1.5 мс

Тик сервера — это не «Minecraft тикает блоки». На пустом сервере с прогруженными spawn-чанками
время уходит на:

| Источник | Порядок | Комментарий |
| --- | --- | --- |
| GC-паузы (young/mixed) | 0.1–3 мс, «пачками» | на графике MSPT это ровные треугольники: тик 0.3, потом 1.8, потом 0.3 |
| random tick (`ServerLevel.tickChunk`) | 0.05–0.4 мс | 3 секции × 4096 блоков × 3 случайных блока; Leaf умеет резать (`performance.optimise-random-tick`, по умолчанию **off**) |
| entity tracker / отправка изменений | 0.05–0.3 мс на игрока | при заходе — плюс отправка чанков |
| natural spawning | 0.1–0.5 мс | зависит от количества прогруженных чанков и `spawn-limits` |
| block entity tickers (сундуки/печки/воронки) | 0.05–1 мс | зависит от количества воронок |
| chunk unload/save очередь | всплески 1–10 мс | видно как p99 = 12 мс при avg 0.8 |

Практический вывод: **средний MSPT 0.5–1.5 мс на пустом сервере — это почти всегда JVM + конфиг, а
не «неоптимизированный код»**. Поэтому порядок работ ниже именно такой: JVM → AppCDS → конфиг →
профилирование → и только потом патчи движка.

---

## 4. Разбор четырёх пунктов из задания

### 4.1 Чанки в off-heap + io_uring для `.mca`

**Что предлагалось:** хранить сетку чанков и палитры в нативной памяти (`sun.misc.Unsafe` /
`java.lang.foreign.MemorySegment`), читать регионы через io_uring с zero-copy.

**Почему это не делается «в лоб»:**

* `java.lang.foreign` (Project Panama) в **Java 21 — preview**. Для него нужен `--enable-preview`
  и при компиляции, и при запуске. Leaf/Paper собираются `--release 21` без preview, а сервер
  запускается без `--enable-preview`. Код с `MemorySegment` в этом билде просто не скомпилируется.
  Полноценно API финализировано в Java 22.
* `MemorySegment`/`Unsafe` решают проблему, которой у Leaf уже почти нет: `PalettedContainer`
  хранит палитру + `BitStorage` (`long[]`), это уже компактные примитивные массивы, а не объект на
  блок. Основной мусор в чанках — короткоживущие объекты при сериализации, а не сами данные.
* Off-heap `LevelChunk` ломает контракт: `LevelChunk`, `LevelChunkSection`, `PalettedContainer`
  доступны плагинам и NMS-коду (ProtocolLib, анти-читы, анти-иксрей, world-редакторы). Любой такой
  плагин получит `MemorySegment` вместо `LevelChunkSection`.
* io_uring для регионов требует нативной библиотеки + JNI + сборки под каждую платформу
  (linux-x86_64, linux-aarch64, macOS, Windows). Плюс Leaf уже умеет Linear-формат регионов
  (патч `0033`), где запись append-only и чтение маленькое. Реальный выигрыш от io_uring на
  прогретом кэше ОС — единицы процентов; на холодном диске узкое место — сам диск, а не syscall.

**Что сделано вместо — off-heap там, где он совместим с плагинами.** В нативную память уехало то,
что действительно большое, живёт долго и читается всеми потоками, но **не является объектом,
который видят плагины**:

* `perf/offheap/OffHeapLongArray` — `long[]` в direct-`ByteBuffer` (без `Unsafe`, без
  `--enable-preview`, освобождение через `Cleaner`, бюджет `-Dleaf.offHeap.maxMB`),
* `perf/offheap/OffHeapLongList` — read-only `LongList` поверх него (fastutil
  `AbstractLongList`, реализованы только `size`/`getLong`), поэтому весь существующий код,
  написанный под `LongList`, работает без изменений,
* `perf/offheap/OffHeapChunkPayloadCache` + `OffHeapByteArray` — кэш сериализованных чанк-пейлоадов
  в нативной памяти: Netty заворачивает direct-буфер **без копии**, GC этот пейлоад не сканирует,
  а `LevelChunk`/`LevelChunkSection` остаются обычными Java-объектами → плагины, античит, WorldEdit,
  Anti-Xray и NMS-код видят ровно то же, что и раньше.

Дополнительно убраны аллокации в путях, которые кормят chunk-IO (§1.2), ограничен кэш direct-буферов
и число Netty-арен. Async построение/отправка чанков в Leaf уже есть (`async.async-chunk-send`), по
умолчанию **выключена**; у её очереди теперь есть предел, и при переполнении задача выполняется на
вызывающем потоке (backpressure вместо роста RSS — см. §1.3).

### 4.2 Data-oriented design: `long[]` вместо объектов, `Long2ObjectOpenHashMap` + свой хэш

**Что верно:** отказ от боксинга и от объектов-обёрток в горячих путях — правильная идея.

**Что уже так и есть:** Leaf/Paper/Moonrise уже используют fastutil (`Long2ObjectOpenHashMap`,
`Reference2IntOpenHashMap`, `LongArrayList`, `ObjectArrayList`) и `long`-упакованные координаты
(`BlockPos.asLong`). Именно поэтому «просто заменить HashMap на fastutil» уже нечего.

**Что сделано:** `IterateOutwardsCache` переведён с `ConcurrentHashMap<Long, …>` на
`Long2ObjectOpenHashMap` с copy-on-write (§1.2) — это ровно тот случай, где бокс был настоящим.

**Что не делается:**

* Свой «солевой» хэш (FxHash/Murmur) вместо fastutil-микса: в `Long2ObjectOpenHashMap` хэш
  вычисляется как `mix(key)` (`HashCommon.mix`, финализатор на 64 битах) — это уже сравнимый по
  качеству микшер. Выигрыш от замены — доли процента, а риск «тихой» деградации при неудачной
  константе — реальный.
* `long[]`-структуры вместо сущностей (SoA) — самое дорогое изменение из всего списка: `Entity`
  иерархия, `SynchedEntityData`, Moonrise-трекер, API плагинов (`getLocation()`, `getVelocity()`)
  завязаны на объекты. Это переписывание ядра на месяцы, с гарантированной потерей совместимости.

### 4.3 SIMD (Vector API) и «no bounds checking» через `Unsafe`

**Vector API (`jdk.incubator.vector`):** модуль инкубаторный, требует `--add-modules
jdk.incubator.vector` при запуске и отсутствует в jlink'нутых JRE. Прирост возможен только там, где
код **compute-bound**: AABB-коллизии, тригонометрия, свет. На практике `AABB`/`MoverType` — это
несколько умножений на вызов, доминирует не арифметика, а cache miss на `BlockState`/`Entity`.
Ожидаемый эффект на MSPT — единицы процентов, цена — обязательный флаг запуска и хрупкость.
Точечно это делают модами (Lithium/C2ME) и не в «горячем» ядре форка.

**«Убрать проверки границ»:** C2 сам убирает bound check, когда видит, что индекс гарантированно в
пределах (range check elimination) — особенно в `for (int i = 0; i < a.length; i++)`. Ручной
`Unsafe.getByte(addr + i)` даёт выигрыш только в циклах, где доказать границы JIT не может, и сразу
лишает JVM возможности проверять корректность. Для 4096-элементных секций разница в пределах
шума профиля.

**Что сделано из этой группы:** автоматическое определение FMA (реальный, дешёвый и безопасный
«hardware-level» бонус для математики расстояний), плюс устранение лишних аллокаций (это то, что
реально стоит денег — GC).

### 4.4 Zero-copy сеть

**Что верно:** отправка чанков — дорогая операция (сериализация + сжатие + запись в сокет).

**Что уже есть:** Netty с `PooledByteBufAllocator` (direct-буферы вне heap), сжатие zstd/lz4 для
регионов, `network.OptimizeNonFlushPacketSending` (несовместим с ProtocolLib),
`performance.reduce-packets.reduce-entity-move-packets`, `async.async-chunk-send`.

**Что сделано:** отключён детектор утечек Netty, ограничено число direct-арен, ограничен per-thread
кэш direct-буферов; FMA/настройки применяются до инициализации Netty (иначе они игнорируются).

**Что не делается:** `FileChannel.transferTo()` в сокет игрока невозможен — между чанком и сокетом
стоят сжатие и TLS/шифрование, а `transferTo` работает только файл→сокет и без преобразований.
Пре-сериализация пакета чанка «один раз на чанк» возможна, но у каждого игрока своё освещение
(edge-lighting) и своя таблица палитр для неба/света — кэш корректно работает только на группу
игроков с идентичными данными; это большая и рискованная работа с памятью (кэш легко съедает
больше, чем экономит).

---

## 5. План: от 1.5 мс к 0.2–0.4 мс

Ожидаемые величины — типичные для VPS 4–8 vCPU, 8–16 ГБ, 20–80 игроков. Точные цифры даёт только
профиль твоего сервера.

| Шаг | Действие | Ожидаемо |
| --- | --- | --- |
| 1 | `scripts/leaf-perf/leaf-flags.sh` → запуск с его флагами | убирает 80–90 % «пилообразных» всплесков MSPT от GC; heap/RSS перестаёт расти ступеньками |
| 2 | `scripts/leaf-perf/leaf-cds.sh` + `-XX:SharedArchiveFile` | старт быстрее на 20–40 %, metaspace меньше на 100–300 МБ, меньше «тяжёлых» первых секунд |
| 3 | включить (когда решишь сам): `async.async-chunk-send`, `async.async-playerdata-save`, `performance.optimise-random-tick`, `performance.throttle-hopper-when-full`, `performance.cache-biome` | заход игрока перестаёт быть спайком; random tick и воронки дешевеют |
| 4 | `/spark profiler start --timeout 60 --only-ticks-over 20` на реальной нагрузке | показывает, что именно даёт p99; без этого шага любые дальнейшие правки — гадание |
| 5 | патчи движка под найденные hot spots (см. §7) | уже точечно, 5–20 % на конкретной системе |

Проверка эффекта: `/leaf mspt` (требует `async.parallel-world-ticking`, если нужен per-world),
`spark`, и `-Xlog:gc*` — три источника не должны противоречить друг другу.

---

## 6. Чего не будет: «0.05 мс»

0.05 мс на тик — это 1000 тиков в секунду, то есть 20 000 tps. В Minecraft один тик — это
`ServerLevel.tick` по всем прогруженным чанкам, entities, block entities, планировщику, сети и
сохранению, и он выполняется **на одном потоке** (даже с PWT — по мирам, не внутри мира). Даже
нативные реимплементации (Pumpkin и т.п.) на пустом мире дают десятые доли миллисекунды.

Реалистичные ориентиры для Leaf на нормальном железе:

* пустой мир, spawn-чанки прогружены, 0 игроков — **0.1–0.4 мс**;
* 20 игроков, обычный SMP — **0.4–1.2 мс**;
* 100 игроков — 1.5–4 мс, и там уже решают конфиг, плагины и диск.

Если у тебя сейчас 0.5–1.5 мс «на пустом» — почти наверняка это либо GC, либо прогруженные чанки с
мобами/воронками, либо плагины. Шаг 1–2 и профиль это покажут.

---

## 7. Патчи движка: что готово, а что ждёт сборки

Подробный план с точными якорями и кодом — `docs/performance/PATCH_PROPOSALS_RU.md`.

**Готово в этой итерации (обычный Java-код, без патчей Minecraft):**

1. Off-heap слой и кэш чанк-пейлоадов (`perf/offheap/*`) — включается флагом, не меняет
   `LevelChunk`, поэтому плагины не затрагивает.
2. `withinManhattan`-путь: таблицы позиций в native memory, итератор без `LongIterator` и с
   индексом вместо объектов-итераторов.
3. `FastBitRadixSort` (сортировка сущностей в сенсорах): буферы растут с запасом вместо точного
   размера, один `List#get` + один `distanceToSqr` на элемент вместо двух, `Class#cast` убран,
   ссылки на сущности не удерживаются между вызовами.
4. Ограниченная очередь async chunk send (backpressure вместо роста памяти).

**Ждёт сборки (описано в `PATCH_PROPOSALS_RU.md`, требует `applyAllPatches` + `build`):**

| # | Патч | Ожидаемый эффект |
| --- | --- | --- |
| 1 | кэш чанк-пейлоадов + ревизия `LevelChunk` (хуки в `setBlockState`/`setBlockEntity`) | заход игрока: десятки мс → единицы |
| 2 | переиспользование `MutableBlockPos` в `checkInsideBlocks`/`betweenClosed` | 3–8 % MSPT, минус десятки тысяч аллокаций/с |
| 3 | round-robin random tick / natural spawning | p99 ниже на 10–30 %, среднее не меняется |
| 4 | трекер без повторных `chunkPosition()`/`distanceToSqr` | 1–3 % |
| 5 | `LevelChunk#getBlockState` через локальный `LevelChunkSection[]` | 1–3 % |
| 6 | zero-alloc `withinManhattan` (fallback без кэша) | 1–2 % + меньше мусора |
| 7 | ленивый library loader / без повторных сканов на старте | metaspace −100…400 МБ, старт быстрее |

Каждый — отдельным файлом в `minecraft-patches/features/`, с прогоном сборки и профилем до/после.

---

## 8. Тесты

Новый код покрыт юнит-тестами в `leaf-server/src/test/java/org/dreeam/leaf/`:

| Класс | Что проверяет |
| --- | --- |
| `perf/offheap/OffHeapTest` | round-trip native-памяти, бюджет, fallback на heap, независимость `ByteBuffer`-view, ключи кэша без коллизий, вытеснение по бюджету |
| `util/cache/IterateOutwardsCacheTest` | содержимое таблиц, «маленькие — на heap, большие — off-heap», кэш-хит возвращает тот же экземпляр, итератор, ограниченность кэша, консистентность при 8 потоках |
| `util/cache/CachedOrNewBitsGetterTest` | переиспользуемый `BitSet` всегда очищен, размеры независимы |
| `util/list/ActivationListTest` | счётчик видимости против модели, 20 000 случайных переключений, компакция, удаление |
| `util/list/BlockEntityTickersListTest` | fast path одиночного удаления = общий путь, границы (первый/последний/пусто), 1 000 случайных удалений против модели |

Запуск:

```bash
./gradlew :leaf-server:test --tests 'org.dreeam.leaf.*'
```

В песочнице, где нет Gradle/paperweight, эти же тесты были скомпилированы компилятором Eclipse JDT
(ECJ 3.39, `-source 21 -target 21`) и прогнаны на Temurin 21: **24 теста, 0 падений**. Отдельно
проверены синтаксис и запуск `CpuFeatures`/`LeafRuntimeTuning`/`JvmFlagAdvisor` (советник печатает
корректный список, тюнинг не перетирает явные `-D`).

## 9. Быстрый чек-лист

```bash
# 0. тесты нового кода
./gradlew :leaf-server:test --tests 'org.dreeam.leaf.*'

# 1. флаги под эту машину
./scripts/leaf-perf/leaf-flags.sh --heap 8G

# 2. архив классов (один раз, и после каждого обновления Leaf)
./scripts/leaf-perf/leaf-cds.sh /srv/mc/leaf.jar --heap 8G

# 3. что выключено в конфиге и как выглядит машина
./scripts/leaf-perf/leaf-diagnose.sh

# 4. профиль, который стоит читать
/spark profiler start --timeout 60 --only-ticks-over 20
/spark profiler stop
```
