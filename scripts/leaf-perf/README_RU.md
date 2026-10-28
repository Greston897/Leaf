# leaf-perf — запуск, память, диагностика

Три скрипта, которые не меняют ни конфиг, ни поведение сервера: они только считают правильные
параметры JVM и показывают, где теряется время.

## 1. `leaf-flags.sh` — генератор JVM-флагов

```bash
./leaf-flags.sh                      # посмотреть, что подходит этой машине
./leaf-flags.sh --heap 8G            # фиксированный heap
./leaf-flags.sh --gc zgc             # g1 | zgc | parallel
eval "$(./leaf-flags.sh --export)" && java $LEAF_JVM_FLAGS -jar leaf.jar --nogui
```

Что он делает:

| Группа | Зачем |
| --- | --- |
| `-Xms == -Xmx`, `-XX:+AlwaysPreTouch` | heap выделяется и «трогается» на старте. Никаких page fault'ов и расширения heap внутри тика — это главный источник «рандомных» всплесков MSPT на пустом сервере |
| G1 с `MaxGCPauseMillis=200`, `G1NewSizePercent=30`, `InitiatingHeapOccupancyPercent=15` | молодое поколение большое, mixed GC редкий; паузы перестают попадать в тик |
| Generational ZGC для heap ≥ 16 ГБ | паузы < 1 мс даже на больших heap; для 4–8 ГБ G1 обычно быстрее по пропускной способности |
| `-XX:MaxMetaspaceSize=512m`, `-XX:MetaspaceSize=256m` | ограничивает metaspace, который раздувает Spigot library loader (это те самые «+2 ГБ») |
| `-XX:MaxDirectMemorySize=<heap/2>` | Netty и чанк-буферы учитываются отдельно, сервер не может незаметно съесть всю RAM |
| `-XX:+PerfDisableSharedMem` | JVM перестаёт писать hsperfdata в `/tmp` с тиковых потоков (известный источник jitter'а) |
| `-Djdk.nio.maxCachedBufferSize=262144` | per-thread кэш direct-буферов перестаёт держать многомегабайтные буферы «навсегда» |
| `-Dio.netty.leakDetection.level=disabled` | детектор утечек Netty не тратит CPU на сэмплирование в продакшене |
| `--enable-native-access`, `-XX:+UseCompactObjectHeaders` | только если JDK реально это поддерживает (22+/24+) |

Флаги проверены на реальной JVM 21: G1/ZGC/Parallel-наборы принимаются без предупреждений.
Флаги для JDK 22+/24+ включаются автоматически только на соответствующей версии.

## 2. `leaf-cds.sh` — AppCDS-архив (быстрее старт, меньше metaspace)

```bash
./leaf-cds.sh /srv/mc/leaf.jar --heap 8G
# далее в стартовой команде:
#   -XX:SharedArchiveFile=/srv/mc/leaf-cds.jsa
```

Скрипт один раз поднимает сервер, ждёт строку `Done (`, аккуратно гасит его по SIGTERM — JVM
записывает архив классов. При следующем старте метаданные классов **ммap'ятся** из файла, а не
парсятся заново: быстрее запуск, меньше metaspace, часть памяти архива вообще разделяется между
процессами (актуально, если на машине несколько серверов).

Пересобирать архив нужно после каждого обновления Leaf (меняется список классов).

## 3. `leaf-diagnose.sh` — что не так с машиной и конфигом

```bash
./leaf-diagnose.sh
# или
./leaf-diagnose.sh --java /opt/jdk-21/bin/java
```

Показывает: THP, swappiness, ulimits, версию JVM, heap, включённые/выключенные
`performance.*`/`async.*` переключатели Leaf (только читает `config/leaf-global.yml`), и готовые
команды для spark / JFR.

## Off-heap: что вынесено и какие флаги

| Флаг | По умолчанию | Что делает |
| --- | --- | --- |
| `-Dleaf.offHeap=false` | `true` | полностью выключает off-heap слой (всё остаётся на heap) |
| `-Dleaf.offHeap.maxMB=64` | `64` | бюджет нативных таблиц позиций (`withinManhattan`) |
| `-Dleaf.offHeap.minEntries=1024` | `1024` | таблицы меньше этого остаются на heap (там они быстрее) |
| `-Dleaf.offHeap.chunkCacheMB=0` | `0` (выкл) | бюджет кэша сериализованных чанк-пейлоадов; включать только после патча ревизии чанка |
| `-Dleaf.offHeap.chunkCacheMaxEntryKB=512` | `512` | записи крупнее не кэшируются |
| `-Dleaf.asyncChunkSend.queueSize=16384` | `16384` | предел очереди async chunk send (0 = без предела, старое поведение) |

Всё вынесенное — immutable данные, которые GC раньше сканировал на каждом цикле: таблицы позиций
`withinManhattan` (радиус 16 → ~48 КиБ, живут весь аптайм) и, если включить, сериализованные
пейлоады чанков. `LevelChunk`/`LevelChunkSection` остаются обычными объектами, поэтому плагины,
античиты и Anti-Xray работают как раньше.

## Память: откуда берутся «+2 ГБ»

По порядку вероятности:

1. **Metaspace** — Spigot library loader грузит jar'ы библиотек, каждый класс занимает место вне
   heap, и без `-XX:MaxMetaspaceSize` это не ограничено. Плюс AppCDS уменьшает саму метадату.
2. **Direct memory** — Netty-арены, чанк-буферы, сжатие. Без `-XX:MaxDirectMemorySize` это «невидимая»
   память вне heap, которая не видна в `-Xmx`.
3. **`AlwaysPreTouch` без `-Xms == -Xmx`** / heap, растущий по мере нагрузки — RSS растёт ступеньками.
4. **Кэш файлов ОС** — это не память JVM, но в `top` она выглядит как занятая (и её отдадут при
   необходимости). Не путать с утечкой.

`leaf-flags.sh` закрывает пункты 1–3 явными лимитами.
