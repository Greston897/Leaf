# Leaf — патчи движка: что доделать в `minecraft-patches`

Этот файл — не готовые `.patch`-файлы, а **точный план патчей** с якорями и кодом, которые можно
применить к `leaf-server/minecraft-patches/features/`. Почему не готовые патчи: их нельзя проверить
без сборки (нет JDK и доступа к `repo.papermc.io` в песочнице), а непроверенный хунк = сломанная
сборка. Ниже — по каждому пункту: где именно, что менять, какой риск и как проверить.

Классы, упомянутые в пунктах 1–7 из `OPTIMIZATION_RU.md §7`, идут в том же порядке.

---

## 1. Кэш сериализованных чанк-пакетов (off-heap) — главный пункт

**Готово в коде:** `org.dreeam.leaf.perf.offheap.OffHeapChunkPayloadCache` +
`OffHeapByteArray`. Кэш уже написан, работает, не включён сам по себе (нужен ключ с ревизией
чанка, иначе можно отдать игроку устаревший чанк).

**Что нужно в патчах:**

### 1.1 Ревизия чанка

`net/minecraft/world/level/chunk/LevelChunk.java`:

```java
// Leaf start - off-heap chunk payload cache
private int leafContentRevision;

public int leafContentRevision() {          // read by the packet builder
    return this.leafContentRevision;
}

private void leafBumpRevision() {           // called from the mutation points below
    ++this.leafContentRevision;
}
// Leaf end
```

Инкремент — ровно там, где меняется видимое клиенту содержимое:

| Метод | Почему |
| --- | --- |
| `LevelChunk#setBlockState(BlockPos, BlockState, boolean)` | блоки |
| `LevelChunk#addAndRegisterBlockEntity` / `removeBlockEntity` | block entity sync |
| `LevelChunk#setBlockEntity` (NBT override) | block entity NBT |
| `LevelChunk#replaceWithPacketData` | клиентские чанки — не трогаем, у них нет сериализации |
| `LevelChunk#setBiome` (через `LevelChunkSection#setBiome`) | биомы видны клиенту при `sendBiomes` |

Секции (`LevelChunkSection#setBlockState` / `setBiome`) сами ревизию не знают — их вызывают только
через `LevelChunk`, поэтому одного хука в `LevelChunk` достаточно.

### 1.2 Ключ кэша

Ключ уже реализован и покрыт тестами — `OffHeapChunkPayloadCache.key(dimensionHash, chunkX,
chunkZ, revision, antiXray)`. В патче остаётся только передать в него поля:

```java
// Leaf start - off-heap chunk payload cache
final long key = org.dreeam.leaf.perf.offheap.OffHeapChunkPayloadCache.key(
    level.dimension().location().hashCode(),   // dimension: same coords exist in every world
    chunk.getPos().x,
    chunk.getPos().z,
    chunk.leafContentRevision(),               // bumped on every visible change, see 1.1
    shouldModify                                // anti-xray changes the serialised bytes
);
// Leaf end
```

Тест `OffHeapTest#cacheKeysAreStableAndSpreadOut` проверяет, что ключи не коллизируют на сетке
16×16×4 ревизий и что смена ревизии/anti-xray/измерения меняет ключ.

### 1.3 Использование при сборке пакета

`net/minecraft/network/protocol/game/ClientboundLevelChunkPacketData.java`, в конструкторе, который
строит `this.buffer` (в Leaf он уже переписан под async chunk send — см. патч `0125`):

```java
// Leaf start - off-heap chunk payload cache
if (org.dreeam.leaf.perf.offheap.OffHeapChunkPayloadCache.enabled()) {
    final long key = /* key from 1.2 */;
    final var cached = org.dreeam.leaf.perf.offheap.OffHeapChunkPayloadCache.instance().get(key);
    if (cached != null) {
        this.leafOffHeapPayload = cached;   // new field, see 1.4
        // heightmaps and block entity list still have to be read from the chunk - they are cheap
        // compared to the 16 section encode that we just skipped.
        this.buffer = null;
        // ... fill this.blockEntitiesData as before ...
        return;
    }
    // miss: encode exactly as today, then
    org.dreeam.leaf.perf.offheap.OffHeapChunkPayloadCache.instance().put(key, this.buffer);
}
// Leaf end
```

### 1.4 Отправка без копии

`net/minecraft/network/protocol/game/ClientboundLevelChunkWithLightPacket.java` — метод `write`:

```java
// Leaf start - off-heap chunk payload cache
if (this.chunkData.leafOffHeapPayload() != null) {
    // zero copy: Netty wraps the native buffer, no heap byte[], no array copy
    buf.writeBytes(io.netty.buffer.Unpooled.wrappedBuffer(this.chunkData.leafOffHeapPayload().view()));
} else {
    this.chunkData.write(buf);
}
// Leaf end
```

Статистика кэша печатается один раз при остановке сервера
(`OffHeapChunkPayloadCache.installShutdownReport()`): хиты, промахи, процент попаданий и занятая
нативная память — сразу видно, окупается кэш или нет.

**Риск:** средний. Неверная ревизия → игрок видит старый блок. Поэтому:
* включать только при `-Dleaf.offHeap.chunkCacheMB=…` (по умолчанию выключено),
* в `LevelChunk#setBlockState` инкремент делать до `return`, безусловно,
* валидация: `/setblock` + F3+G, вода/лава flow, поршни, взрыв, `//set` из WorldEdit, перестройка
  освещения (свет **не** в кэше — `ClientboundLightUpdatePacketData` строится отдельно, как и
  раньше).

**Проверка:** spark-профиль захода игрока до/после; `usedBytes()/hits()/misses()` в
shutdown-логе; визуальная проверка «блок поставил — ушёл — вернулся».

---

## 2. Переиспользование курсоров в горячих циклах

`net/minecraft/world/entity/Entity.java#checkInsideBlocks` и `#onInsideBlock`:

```java
// Leaf start - reuse cursor
private final BlockPos.MutableBlockPos leafCursor = new BlockPos.MutableBlockPos();
// Leaf end
```

и внутри цикла вместо `BlockPos.betweenClosed(...)` / `blockPos.immutable()` — один
`MutableBlockPos`:

```java
for (int x = ...; ...) {
    this.leafCursor.set(x, y, z);
    BlockState state = level.getBlockState(this.leafCursor);
    ...
}
```

То же для:
* `net/minecraft/world/level/Level#getBlockStatesIfLoaded` (используется в `AABB` проверках),
* `net/minecraft/world/entity/ai/behavior/…` где есть `BlockPos.betweenClosed` (см. `grep -rn
  "betweenClosed"` после `applyAllPatches`),
* `net/minecraft/world/level/block/…` где в цикле создаётся `new BlockPos(...)`.

**Риск:** низкий (локальный курсор, наружу не утекает). **Правило:** если курсор передаётся в
публичный API или в плагин — `.immutable()`.

---

## 3. Разнести random tick и natural spawning по тикам

`net/minecraft/server/level/ServerChunkCache.java#tickChunks`:

```java
// Leaf start - spread heavy per-chunk work
// Both loops already run once per tick over every ticking chunk. Doing both in the same tick
// doubles the peak; alternating them keeps the total work identical while halving the worst tick.
final boolean doRandomTick = (this.level.getGameTime() & 1L) == 0L;
final boolean doSpawning   = !doRandomTick;
// Leaf end
```

и обернуть существующие вызовы (`this.level.tickChunk(...)` / `this.level.tickChunkSpawner(...)`
— точное имя зависит от версии, ищи `tickChunks` и `naturalSpawn`).

**Риск:** низкий-средний (меняется фазировка, не количество). Мобы будут спавниться «через тик» —
на 20 TPS это незаметно, но на 5 TPS влияет на скорость спавна. Поэтому — отдельным патчем и с
проверкой `spawn-limits`.

---

## 4. Трекер без повторных вычислений

`ca/spottedleaf/moonrise/.../ChunkMap$TrackedEntity` (`updatePlayer`, `updatePlayers`) и
`net/minecraft/server/level/ChunkMap`:

* закешировать `entity.chunkPosition()` в локальную переменную (сейчас вызывается 2–4 раза на
  трекер за тик, каждый вызов — `SectionPos.blockToSectionCoord` дважды),
* `ServerEntity#sendChanges` — не вызывать, если `tracker.moonrise$hasPlayers()` ложно и чанк не
  `ENTITY_TICKING` (в Leaf это уже частично сделано в `MultithreadedTracker#tickOriginal`),
* заменить `new ArrayList<>()` на переиспользуемый массив в `ServerChunkCache#broadcast`.

**Риск:** низкий. **Проверка:** количество пакетов `spark profiler` → `ServerEntity#sendChanges`.

---

## 5. `LevelChunkSection` без bound-check в горячем цикле

`net/minecraft/world/level/chunk/LevelChunk#getBlockState` — сейчас `getSection(...)` на каждый
вызов, плюс `LevelChunkSection#getBlockState` делает `(y & 15) << 8 | (z & 15) << 4 | (x & 15)`.

Безопасный вариант (без `Unsafe`, JIT сам убирает проверки границ):

```java
// Leaf start - avoid a second array lookup per block read
LevelChunkSection[] sections = this.sections;   // local, JIT can prove the length
int index = (y >> 4) + this.minSectionIndex;    // same arithmetic as getSection()
if (index < 0 || index >= sections.length) return Blocks.VOID_AIR.defaultBlockState();
LevelChunkSection section = sections[index];
// Leaf end
```

**Риск:** низкий, но требует точного совпадения с `getSection()` (в Leaf `minSectionIndex` уже
есть). **Проверка:** тест `craftbukkit` на `getBlockAt` вне мира; сравнение результатов на
дампе 100k позиций.

---

## 6. `withinManhattan`/`betweenClosed`: добить zero-alloc

**Уже сделано:** кэш таблиц (heap→off-heap) и индексатор без `LongIterator` — см.
`IterateOutwardsCache` / `LongList2BlockPosMutableIterable`.

**Осталось:** сам `BlockPos#withinManhattan` (fallback без кэша) аллоцирует `BlockPos[]`:

```java
// было
BlockPos[] apos = new BlockPos[count];
for (...) apos[i++] = new BlockPos(...);
return Arrays.asList(apos);
```

Замена — тот же ленивый итератор, что в `LongList2BlockPosMutableIterable`, но без списка:

```java
// Leaf start - zero allocation Manhattan walk
return () -> new Iterator<BlockPos>() {
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private int remaining = count;
    // ... same traversal order as the vanilla array fill ...
};
```

**Риск:** средний — порядок обхода нужно сохранить байт-в-байт, его используют сенсоры.
**Проверка:** юнит-тест «для 10k случайных (x,y,z,range) последовательность `asLong()` совпадает с
ванильной».

---

## 7. Старт: metaspace, library loader, сканы

`org/bukkit/plugin/java/JavaPluginLoader` + `io/papermc/paper/plugin/…`:

* `LibraryLoader#loadLibrary` — Spigot library loader пишет и грузит **все** jar'ы библиотек
  эагерно. Ленивый `URLClassLoader` с одним общим parent + `isLoaded` проверкой до `defineClass`
  уменьшает metaspace (в среднем 100–400 МБ на сервер с 20 плагинами).
* `PluginScanner` — `ClassGraph` сканирует весь classpath на старте; ограничить
  `enableClassInfo()`/`ignoreClassVisibility()` тем, что реально нужно для `getPlugins`.
* `PaperPluginParent`/`PaperPluginClassLoader` — не создавать `JarFile` дважды на плагин.

**Риск:** средний (загрузка плагинов — чувствительная зона). **Проверка:** сервер с 30–50 плагинами,
сравнить `used metaspace` до/после и список загруженных классов.

---

## Порядок применения

1. Пункты 2, 4, 5, 6 — механически безопасные, дают 3–8 % MSPT на загруженном сервере.
2. Пункт 3 — фазировка, 10–30 % по p99 при том же среднем.
3. Пункт 1 (кэш) — самый большой выигрыш на заходах (десятки миллисекунд на игрока), но требует
   аккуратной ревизии; включать флагом.
4. Пункт 7 — старт и metaspace, отдельная итерация.

Каждый пункт — отдельный файл в `minecraft-patches/features/`, с прогоном
`./gradlew applyAllPatches` и `./gradlew build`, и с профилем до/после.
