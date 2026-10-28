#!/usr/bin/env bash
# ==============================================================================
#  Leaf - AppCDS / AOT archive builder
# ==============================================================================
#  A Minecraft server loads tens of thousands of classes at boot. Two thirds of
#  them are identical on every start:
#
#    * AppCDS stores the parsed class metadata in a memory mapped archive, so the
#      JVM maps the metadata instead of parsing it again -> faster startup and a
#      smaller, shared metaspace (this is the honest part of the "library loader
#      eats 2 GB" story: metaspace is what actually grows),
#    * a side effect is that the archive file lives in the OS page cache and is
#      shared between processes, so a restart does not re-parse anything.
#
#  Usage:
#    ./leaf-cds.sh /path/to/leaf-1.21.4.jar                 # build the archive
#    ./leaf-cds.sh /path/to/leaf-1.21.4.jar --heap 8G       # with a fixed heap
#    ./leaf-cds.sh /path/to/leaf-1.21.4.jar --dir /srv/mc   # where the archive goes
#
#  Afterwards start the server with:
#    java $LEAF_JVM_FLAGS -XX:SharedArchiveFile=/srv/mc/leaf-cds.jsa -jar leaf.jar --nogui
#
#  The script boots the server once (that is unavoidable: CDS archives the classes
#  the application really touched), waits for the "Done" line, and stops it again
#  with SIGTERM so the archive is written during a normal shutdown.
# ==============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR=""
DIR="$(pwd)"
HEAP=""
GC="auto"
JAVA_BIN="${JAVA_BIN:-java}"
BOOT_TIMEOUT="${BOOT_TIMEOUT:-300}"

while [ $# -gt 0 ]; do
    case "$1" in
        --heap)    HEAP="${2:?}"; shift 2 ;;
        --heap=*)  HEAP="${1#*=}"; shift ;;
        --gc)      GC="${2:?}"; shift 2 ;;
        --gc=*)    GC="${1#*=}"; shift ;;
        --dir)     DIR="${2:?}"; shift 2 ;;
        --dir=*)   DIR="${1#*=}"; shift ;;
        --java)    JAVA_BIN="${2:?}"; shift 2 ;;
        --java=*)  JAVA_BIN="${1#*=}"; shift ;;
        --timeout) BOOT_TIMEOUT="${2:?}"; shift 2 ;;
        -h|--help) sed -n '2,32p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        -*)        echo "unknown option: $1" >&2; exit 2 ;;
        *)         JAR="$1"; shift ;;
    esac
done

if [ -z "$JAR" ]; then
    echo "usage: $0 <server.jar> [--heap 8G] [--gc g1|zgc] [--dir DIR] [--java PATH]" >&2
    exit 2
fi
if [ ! -f "$JAR" ]; then
    echo "error: server jar not found: $JAR" >&2
    exit 2
fi

ARCHIVE="$DIR/leaf-cds.jsa"
LOG="$DIR/leaf-cds-boot.log"

FLAG_ARGS=(--gc "$GC")
if [ -n "$HEAP" ]; then
    FLAG_ARGS+=(--heap "$HEAP")
fi
JVM_FLAGS="$("$HERE/leaf-flags.sh" "${FLAG_ARGS[@]}" --java "$JAVA_BIN" | grep -v '^#')"

echo "==> booting the server once to record the class archive"
echo "    jar:     $JAR"
echo "    archive: $ARCHIVE"
echo "    log:     $LOG"

# shellcheck disable=SC2086
"$JAVA_BIN" $JVM_FLAGS \
    -Dleaf.silentJvmAdvice=true \
    -XX:ArchiveClassesAtExit="$ARCHIVE" \
    -jar "$JAR" --nogui >"$LOG" 2>&1 &
SERVER_PID=$!

cleanup() {
    if kill -0 "$SERVER_PID" 2>/dev/null; then
        kill -TERM "$SERVER_PID" 2>/dev/null || true
        wait "$SERVER_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT INT TERM

deadline=$(( $(date +%s) + BOOT_TIMEOUT ))
ready=0
while [ "$(date +%s)" -lt "$deadline" ]; do
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        break
    fi
    if grep -q 'Done (' "$LOG" 2>/dev/null; then
        ready=1
        break
    fi
    sleep 2
done

if [ "$ready" -ne 1 ]; then
    echo "error: the server did not reach 'Done (' within ${BOOT_TIMEOUT}s, see $LOG" >&2
    exit 1
fi

echo "==> server is up, stopping it gracefully so the archive is written"
kill -TERM "$SERVER_PID" 2>/dev/null || true
wait "$SERVER_PID" 2>/dev/null || true
trap - EXIT INT TERM

if [ ! -f "$ARCHIVE" ]; then
    echo "error: no archive was written (check $LOG); the JDK may need -Xshare:auto" >&2
    exit 1
fi

SIZE="$(du -h "$ARCHIVE" | cut -f1)"
echo
echo "==> done: $ARCHIVE ($SIZE)"
echo "    add this to the start command:"
echo "      -XX:SharedArchiveFile=$ARCHIVE"
echo
echo "    rebuild it after every Leaf update (the class list changes)."
