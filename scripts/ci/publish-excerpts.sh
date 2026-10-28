#!/usr/bin/env bash
# Publish a slice of a generated source file as GitHub annotations.
#
# The Actions log endpoints are not reachable from every environment, while the check run
# annotations API is plain api.github.com - so CI publishes code excerpts (and build failures) as
# annotations, which makes an otherwise unreadable build log readable.
#
# usage:
#   publish-excerpts.sh range  <title> <file> <first-line> <last-line>
#   publish-excerpts.sh method <title> <file> <grep-pattern> [extra-lines]
set -euo pipefail

MODE="${1:?range|method}"
TITLE="${2:?title}"
FILE="${3:?file}"

if [ ! -f "$FILE" ]; then
    echo "::error title=${TITLE}-MISSING::${FILE} does not exist"
    exit 0
fi

if [ "$MODE" = "method" ]; then
    PATTERN="${4:?pattern}"
    EXTRA="${5:-80}"
    START="$(grep -n "$PATTERN" "$FILE" | head -1 | cut -d: -f1 || true)"
    if [ -z "${START:-}" ]; then
        echo "::error title=${TITLE}-NOT-FOUND::pattern '$PATTERN' not found in ${FILE}"
        exit 0
    fi
    FIRST=$(( START > 4 ? START - 4 : 1 ))
    LAST=$(( START + EXTRA ))
else
    FIRST="${4:-1}"
    LAST="${5:-200}"
fi

{
    echo "### ${FILE}:${FIRST}-${LAST}"
    sed -n "${FIRST},${LAST}p" "$FILE"
} > /tmp/excerpt-full.txt

TITLE="$TITLE" python3 - <<'PYEOF'
import os

data = open('/tmp/excerpt-full.txt', encoding='utf-8', errors='replace').read()
limit = 3500
chunks = [data[i:i + limit] for i in range(0, len(data), limit)] or ['']
title = os.environ.get('TITLE', 'EXCERPT')
for index, chunk in enumerate(chunks[:10], start=1):
    escaped = chunk.replace('%', '%25').replace('\r', '').replace('\n', '%0A')
    print('::error title=%s-%d-OF-%d::%s' % (title, index, len(chunks), escaped))
PYEOF
