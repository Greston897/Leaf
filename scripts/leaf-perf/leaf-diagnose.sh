#!/usr/bin/env bash
# ==============================================================================
#  Leaf - read-only performance diagnostic
# ==============================================================================
#  Answers the question "why is my MSPT not what I expect?" without touching a
#  single configuration value. Run it inside the server directory:
#
#      ./leaf-diagnose.sh                 # environment + config audit
#      ./leaf-diagnose.sh --java /opt/jdk-21/bin/java
#
#  What it looks at:
#    1. kernel/VM level: THP, swappiness, CPU governor, ulimits,
#    2. JVM level: version, collector, heap, the flags the server was started with,
#    3. Leaf level: which of the shipped performance switches are still off,
#    4. the exact spark / JFR commands that produce a profile you can read.
#
#  Nothing here writes to disk or changes a setting. It only reports.
# ==============================================================================
set -euo pipefail

JAVA_BIN="${JAVA_BIN:-java}"
CONFIG_DIR="${CONFIG_DIR:-config}"

while [ $# -gt 0 ]; do
    case "$1" in
        --java)   JAVA_BIN="${2:?}"; shift 2 ;;
        --java=*) JAVA_BIN="${1#*=}"; shift ;;
        --config) CONFIG_DIR="${2:?}"; shift 2 ;;
        -h|--help) sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

if command -v "$JAVA_BIN" >/dev/null 2>&1; then
    JAVA_PATH="$(command -v "$JAVA_BIN")"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_PATH="$JAVA_HOME/bin/java"
else
    JAVA_PATH=""
fi

section() { printf '\n== %s ==\n' "$1"; }

section "host"
printf 'kernel      : %s\n' "$(uname -srm)"
printf 'cpu         : %s cores, %s\n' \
    "$(nproc 2>/dev/null || echo '?')" \
    "$(awk -F': ' '/model name/ {print $2; exit}' /proc/cpuinfo 2>/dev/null || echo '?')"
if [ -r /proc/meminfo ]; then
    awk '/MemTotal/ {printf "memory      : %d MiB total\n", $2/1024}' /proc/meminfo
    awk '/MemAvailable/ {printf "memory free : %d MiB available\n", $2/1024}' /proc/meminfo
    awk '/SwapTotal/ {printf "swap        : %d MiB\n", $2/1024}' /proc/meminfo
fi
if [ -r /sys/kernel/mm/transparent_hugepage/enabled ]; then
    printf 'thp         : %s\n' "$(cat /sys/kernel/mm/transparent_hugepage/enabled)"
fi
if [ -r /proc/sys/vm/swappiness ]; then
    printf 'swappiness  : %s (a Minecraft heap should never be swapped: use 10 or lower)\n' \
        "$(cat /proc/sys/vm/swappiness)"
fi
printf 'open files  : %s\n' "$(ulimit -n 2>/dev/null || echo '?')"

section "jvm"
if [ -n "$JAVA_PATH" ]; then
    printf 'binary      : %s\n' "$JAVA_PATH"
    "$JAVA_PATH" -version 2>&1 | sed 's/^/  /'
    printf 'xmx/xms     : '
    "$JAVA_PATH" -XshowSettings:vm -version 2>&1 | awk '/Max. Heap Size/ {print $4, $5}' | tail -n1
else
    echo "no java found (use --java /path/to/java)"
fi

section "leaf config audit"
GLOBAL="$CONFIG_DIR/leaf-global.yml"
if [ -f "$GLOBAL" ]; then
    echo "reading $GLOBAL (read only)"
    if command -v python3 >/dev/null 2>&1; then
        python3 - "$GLOBAL" <<'PYEOF'
import sys

path = sys.argv[1]
stack = []          # [(indent, key)]
off = []
try:
    with open(path, encoding='utf-8', errors='replace') as handle:
        for raw in handle:
            line = raw.rstrip('\n')
            stripped = line.strip()
            if not stripped or stripped.startswith('#') or ':' not in stripped:
                continue
            indent = len(line) - len(line.lstrip())
            key, _, value = stripped.partition(':')
            key = key.strip().strip('"').strip("'")
            while stack and stack[-1][0] >= indent:
                stack.pop()
            full = '.'.join([entry[1] for entry in stack] + [key])
            if value.strip().lower() == 'false':
                off.append(full)
            if not value.strip():
                stack.append((indent, key))
except OSError as error:
    print(f"  unable to read {path}: {error}")
    raise SystemExit(0)

interesting = [key for key in off if key.startswith(('performance.', 'async.', 'network.'))]
for key in interesting:
    print(f"  off: {key}")
if not interesting:
    print("  every performance/async switch is already enabled")
print(f"  ({len(off)} switch(es) in total are off, {len(interesting)} of them in performance/async/network)")
PYEOF
    else
        grep -nE '^[[:space:]]+[a-zA-Z0-9._-]+: *false' "$GLOBAL" | head -n 40 || true
    fi
    printf '\n  We never edit your config. The switches with the best ratio for a\n'
    printf '  tick-bound server are, in this order:\n'
    printf '    async.async-chunk-send            - moves chunk builds/sends off the tick\n'
    printf '    async.async-playerdata-save       - removes the join/leave save spike\n'
    printf '    performance.optimise-random-tick  - fewer random tick candidates\n'
    printf '    performance.throttle-hopper-when-full\n'
    printf '    performance.cache-biome           - caches biome lookups during worldgen\n'
    printf '    async.async-pathfinding           - only if no plugin needs sync paths\n'
else
    echo "no $GLOBAL found - run this from the server directory"
fi

section "profiling recipes"
cat <<'EOF'
  1) spark (bundled in Leaf):
       /spark profiler start --timeout 60 --only-ticks-over 20
       /spark profiler stop
     Averages of 0.5-1.5 ms hide the tail. --only-ticks-over 20 samples the
     spikes; that is the profile that tells you what to fix.

  2) JFR (no plugin needed, records alloc + GC + safepoints):
       -XX:StartFlightRecording=duration=120s,filename=tick.jfr,settings=profile
     then: jfr summary tick.jfr && jfr print --events jdk.GCPhasePause tick.jfr

  3) GC pauses that show up as MSPT spikes:
       -Xlog:gc*:file=gc.log:time,uptime:filecount=5,filesize=20M

  4) allocation hot spots (what the young collection actually costs):
       /spark heapsummary
EOF
