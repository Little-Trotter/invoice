#!/bin/sh
# Prints the buildx builder to use: the remote BuildKit builder when its host is
# reachable, otherwise the local daemon's default builder. Never fails.
# Overrides: LT_BUILDER (builder name), LT_BUILD_HOST (ssh target). Flag -q: no stderr note.
quiet=0
[ "$1" = "-q" ] && quiet=1
note() { [ "$quiet" = 1 ] || echo "docker-builder: $1" >&2; }

if [ -n "${LT_BUILDER:-}" ]; then
  note "using $LT_BUILDER (LT_BUILDER)"
  echo "$LT_BUILDER"
  exit 0
fi

host="${LT_BUILD_HOST:-}"
if [ -z "$host" ]; then
  note "using default (no LT_BUILD_HOST)"
elif docker buildx inspect lt-remote >/dev/null 2>&1; then
  if ssh -o BatchMode=yes -o ConnectTimeout=2 "$host" true >/dev/null 2>&1; then
    note "using lt-remote ($host reachable)"
    echo lt-remote
    exit 0
  fi
  note "using default ($host not reachable)"
else
  note "using default (builder lt-remote not configured)"
fi
echo default
exit 0
