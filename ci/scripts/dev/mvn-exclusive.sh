#!/usr/bin/env bash
#
# Project: Little Trotter X-Invoice
# Copyright (c) 2026 Eifel42 Stefan Zils
# See LICENSE and README.md for license terms and documentation.
#
# Disclaimer: This software is provided as-is, without warranty of any kind. Use at your own risk.

# mvn-exclusive.sh - run one Maven invocation under the MACHINE-WIDE build mutex.
#
# Two Maven runs in different git worktrees each write to their own target/, but Quarkus' test
# port 8081 and the background Kotlin compile daemon are machine-wide, not per tree — two
# concurrent runs from *different* worktrees can still collide on the port or poison the shared
# daemon into silent 0-class compiles. This wrapper serialises every Maven run on the machine,
# whichever tree it starts from, so an agent brief can say "build with
# ci/scripts/dev/mvn-exclusive.sh <mvn args>" and nothing else. The root Makefile's `test` target
# routes through this same wrapper (since 17.09.2026), so a human `make test` and an agent build
# serialize against each other too, not just against other agents.
#
# Token & CO2 Optimization:
#   Set LT_TERSE=true (or pass --terse as the first argument) to filter output through
#   filter-maven-output.py, compacting test runs to essentials (failures, errors, test counts,
#   linter issues, and build status) to save agent LLM context tokens.
#
# Usage (from the root of the tree to build):
#   ci/scripts/dev/mvn-exclusive.sh -pl kotlin-invoice -am verify -Dtest=FooTest -Djacoco.skip=true
#   ci/scripts/dev/mvn-exclusive.sh --terse -pl kotlin-shared test "-Dtest=FooTest*"
#
# The lock lives outside every tree (LT_MAVEN_LOCK, default ~/GitHub/lt-worktrees/.maven-lock)
# and reuses build-lock.sh's own acquire/release contract (waits while the holder is alive,
# steals a lock whose pid is gone).
set -euo pipefail

# Auto-enable terse output if running under known AI coding agents (Claude Code,
# Antigravity/Gemini CLI, Kilo Code, pi-agent) unless explicitly overridden.
is_agent=false
if [ -n "${CLAUDE_CODE:-}" ] || [ -n "${ANTIGRAVITY_AGENT:-}" ] || \
   [ -n "${PI_AGENT:-}" ] || [ -n "${KILO_CODE:-}" ] || \
   [ -n "${GEMINI_CLI:-}" ] || [ -n "${AI_AGENT:-}" ]; then
  is_agent=true
fi

terse="${LT_TERSE:-$is_agent}"
if [ "${1:-}" = "--terse" ]; then
  terse="true"
  shift
fi

here="$(cd "$(dirname "$0")" && pwd)"
lockdir="${LT_MAVEN_LOCK:-$HOME/GitHub/lt-worktrees/.maven-lock}"
mkdir -p "$(dirname "$lockdir")"

bash "$here/build-lock.sh" acquire "$lockdir" "$$"

# The lock must never be released while the Maven JVM is still running. A signal to this wrapper
# (ssh session dropped, caller timed out, agent stopped) used to fire the release trap and leave
# Maven orphaned but alive — the next waiter then started a second build beside it. Maven runs as
# a child that is stopped first; only then is the lock given back.
child=""
release_lock() { bash "$here/build-lock.sh" release "$lockdir" "$$"; }
stop_child() {
  if [ -n "$child" ] && kill -0 "$child" 2>/dev/null; then
    kill "$child" 2>/dev/null || true
    wait "$child" 2>/dev/null || true
  fi
}
trap 'stop_child; release_lock' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP

if [ "$terse" = "true" ]; then
  # Pipe through filter-maven-output.py while preserving Maven's exit code
  set +e
  ./mvnw "$@" < /dev/null 2>&1 | "$here/filter-maven-output.py" &
  child=$!
  wait "$child"
  exit_status=$?
  set -e
  exit $exit_status
else
  ./mvnw "$@" < /dev/null &
  child=$!
  wait "$child"
fi
