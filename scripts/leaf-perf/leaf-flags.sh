#!/usr/bin/env bash
# ==============================================================================
#  Leaf - JVM flag generator (2026 preset)
# ==============================================================================
#  Emits a JVM command line tuned for a Minecraft/Leaf server:
#
#    * G1 (small/medium boxes) or Generational ZGC (large heaps) with sane region
#      sizing, so the collector never leaves a 30-80 ms pause inside a tick,
#    * pre-touched, fixed size heap  -> no page faults while ticking,
#    * capped metaspace              -> the library loader cannot grow the
#                                       resident set by gigabytes unnoticed,
#    * accounted direct memory       -> Netty/chunk buffers stay predictable,
#    * JDK 22/24+ extras only when the running JDK actually supports them.
#
#  Usage:
#    ./leaf-flags.sh                    # print flags for this machine
#    ./leaf-flags.sh --heap 8G          # explicit heap
#    ./leaf-flags.sh --gc zgc           # force a collector: g1 | zgc | parallel
#    ./leaf-flags.sh --export           # print `export LEAF_JVM_FLAGS='...'`
#    eval "$(./leaf-flags.sh --export)" && java $LEAF_JVM_FLAGS -jar server.jar --nogui
#
#  It is read-only: it inspects the machine, prints a command line, touches nothing.
# ==============================================================================
set -euo pipefail

JAVA_BIN="${JAVA_BIN:-java}"
HEAP=""
GC="auto"
MODE="print"

while [ $# -gt 0 ]; do
    case "$1" in
        --heap)   HEAP="${2:?--heap needs a value}"; shift 2 ;;
        --heap=*) HEAP="${1#*=}"; shift ;;
        --gc)     GC="${2:?--gc needs a value}"; shift 2 ;;
        --gc=*)   GC="${1#*=}"; shift ;;
        --java)   JAVA_BIN="${2:?--java needs a value}"; shift 2 ;;
        --java=*) JAVA_BIN="${1#*=}"; shift ;;
        --print)  MODE="print"; shift ;;
        --export) MODE="export"; shift ;;
        -h|--help) sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

# --- environment detection ----------------------------------------------------
java_feature() {
    local bin="$1" raw
    raw="$("$bin" -XshowSettings:properties -version 2>&1 \
        | awk -F'= ' '/java.specification.version/ {print $2; exit}' || true)"
    if [ -z "$raw" ]; then
        raw="$("$bin" -version 2>&1 | head -n1 | sed -E 's/.*"([0-9]+)(\.([0-9]+))?.*/\1/' || true)"
    fi
    if [ "$raw" = "1" ]; then raw=8; fi
    raw="${raw%%.*}"
    case "$raw" in
        ''|*[!0-9]*) echo 21 ;;   # unknown -> assume the minimum Leaf supports
        *) echo "$raw" ;;
    esac
}

detect_java() {
    if command -v "$JAVA_BIN" >/dev/null 2>&1; then
        echo "$(command -v "$JAVA_BIN")"
    elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        echo "$JAVA_HOME/bin/java"
    else
        echo "java"
    fi
}

JAVA_PATH="$(detect_java)"
JAVA_BIN="$JAVA_PATH"
JDK_FEATURE="$(java_feature "$JAVA_PATH")"
CORES="$(nproc 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"

total_mem_mb() {
    if [ -r /proc/meminfo ]; then
        awk '/MemTotal/ {printf "%d\n", $2/1024}' /proc/meminfo
    elif command -v sysctl >/dev/null 2>&1; then
        echo $(( $(sysctl -n hw.memsize 2>/dev/null || echo 4294967296) / 1048576 ))
    else
        echo 4096
    fi
}

MEM_MB="$(total_mem_mb)"

# Heap: 60% of RAM, clamped to [2 GiB, 32 GiB] unless the user asked for something.
if [ -z "$HEAP" ]; then
    HEAP_MB=$(( MEM_MB * 60 / 100 ))
    if [ "$HEAP_MB" -lt 2048 ]; then HEAP_MB=2048; fi
    if [ "$HEAP_MB" -gt 32768 ]; then HEAP_MB=32768; fi
    HEAP="${HEAP_MB}M"
fi

# --- collector choice ---------------------------------------------------------
heap_mb_from() {
    local v="$1"
    case "$v" in
        *[gG]) echo $(( ${v%[gG]} * 1024 )) ;;
        *[mM]) echo "${v%[mM]}" ;;
        *[tT]) echo $(( ${v%[tT]} * 1024 * 1024 )) ;;
        *)     echo $(( v / 1048576 )) ;;   # plain bytes
    esac
}
HEAP_MB="$(heap_mb_from "$HEAP")"

if [ "$GC" = "auto" ]; then
    # Generational ZGC wins on pause time as soon as the heap is big enough that a
    # G1 young collection is not measured in single digit milliseconds any more.
    if [ "$HEAP_MB" -ge 16384 ] && [ "$JDK_FEATURE" -ge 21 ]; then
        GC="zgc"
    else
        GC="g1"
    fi
fi

FLAGS=()
FLAGS+=("-Xms${HEAP}" "-Xmx${HEAP}")

case "$GC" in
    g1)
        FLAGS+=(
            "-XX:+UseG1GC"
            "-XX:+ParallelRefProcEnabled"
            "-XX:MaxGCPauseMillis=200"
            "-XX:+UnlockExperimentalVMOptions"
            "-XX:+DisableExplicitGC"
            "-XX:+AlwaysPreTouch"
            "-XX:G1NewSizePercent=30"
            "-XX:G1MaxNewSizePercent=40"
            "-XX:G1HeapRegionSize=8M"
            "-XX:G1ReservePercent=20"
            "-XX:G1HeapWastePercent=5"
            "-XX:G1MixedGCCountTarget=4"
            "-XX:InitiatingHeapOccupancyPercent=15"
            "-XX:G1MixedGCLiveThresholdPercent=90"
            "-XX:G1RSetUpdatingPauseTimePercent=5"
            "-XX:SurvivorRatio=32"
            "-XX:MaxTenuringThreshold=1"
            "-XX:+UseStringDeduplication"
        )
        ;;
    zgc)
        FLAGS+=(
            "-XX:+UseZGC"
            "-XX:+DisableExplicitGC"
            "-XX:+AlwaysPreTouch"
            "-XX:ZUncommitDelay=300"
        )
        # Generational ZGC is the default from JDK 23 on, explicit in 21/22.
        if [ "$JDK_FEATURE" -ge 21 ] && [ "$JDK_FEATURE" -le 22 ]; then
            FLAGS+=("-XX:+ZGenerational")
        fi
        ;;
    parallel)
        FLAGS+=("-XX:+UseParallelGC" "-XX:+AlwaysPreTouch" "-XX:+DisableExplicitGC")
        ;;
    *)
        echo "unknown --gc value: $GC (expected g1 | zgc | parallel)" >&2
        exit 2
        ;;
esac

# --- memory bookkeeping -------------------------------------------------------
FLAGS+=(
    "-XX:+PerfDisableSharedMem"          # no hsperfdata mmap writes from tick threads
    "-XX:MaxMetaspaceSize=512m"          # cap what Spigot's library loader can nibble
    "-XX:MetaspaceSize=256m"
    "-XX:MaxDirectMemorySize=$(( HEAP_MB / 2 ))M"
    "-Djdk.nio.maxCachedBufferSize=262144"
    "-Djdk.attach.allowAttachSelf=true"
    "-Dfile.encoding=UTF-8"
    "-Djava.awt.headless=true"
    "-Dio.netty.leakDetection.level=disabled"
)

# --- JDK version gated extras -------------------------------------------------
if [ "$JDK_FEATURE" -ge 24 ]; then
    # JEP 450: 8 byte object headers - less heap, better cache locality.
    FLAGS+=("-XX:+UseCompactObjectHeaders")
fi
if [ "$JDK_FEATURE" -ge 22 ]; then
    FLAGS+=("--enable-native-access=ALL-UNNAMED")
fi
if [ "$JDK_FEATURE" -lt 21 ]; then
    echo "warning: Leaf requires Java 21+, detected Java ${JDK_FEATURE}" >&2
fi

# --- output -------------------------------------------------------------------
if [ "$MODE" = "export" ]; then
    printf 'export LEAF_JVM_FLAGS=%q\n' "${FLAGS[*]}"
else
    printf '# java: %s (feature %s)\n' "$JAVA_PATH" "$JDK_FEATURE"
    printf '# machine: %s cores, %s MiB RAM -> heap %s, gc %s\n' "$CORES" "$MEM_MB" "$HEAP" "$GC"
    printf '%s\n' "${FLAGS[*]}"
fi
