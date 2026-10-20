#!/bin/bash
# Generic acquire/release mutex over a lock DIRECTORY, used by ci/scripts/dev/mvn-exclusive.sh
# (the root Makefile's `test` target and every agent build brief go through that wrapper) so only
# one heavy Maven build runs at a time on the machine. Concurrent builds corrupt each other's
# target/ dirs, and under host load can poison the shared background Kotlin compile daemon. This
# script itself takes a plain lockdir argument, not a hardcoded path — mvn-exclusive.sh is what
# picks one that is genuinely shared machine-wide, across every tree/worktree, not a per-caller
# location.
#
# Usage from a caller's shell (one shell, set -e):
#   bash ci/scripts/dev/build-lock.sh acquire <lockdir> $$      # blocks until the lock is ours
#   trap 'bash ci/scripts/dev/build-lock.sh release <lockdir>' EXIT INT TERM
#
# Must run under bash (set -o pipefail is not POSIX sh) — the Makefile invokes it as
# `bash ci/scripts/dev/build-lock.sh ...`, never `sh ci/scripts/dev/build-lock.sh ...`; a
# `/bin/sh` that is dash (Debian/Ubuntu default, e.g. the Forgejo runner image) fails with
# "set: Illegal option -o pipefail" otherwise (2026-09-13 incident).
#
# Install the trap only AFTER acquire returns: a Ctrl-C while still waiting must never delete
# the lock of the build that actually holds it. The recorded PID is the CALLER's shell pid
# (passed in) — this script's own pid dies with it, which would make every lock look stale.
# A lock whose recorded pid is no longer alive is stolen; a leaked lock therefore self-heals.
set -euo pipefail

cmd="${1:?usage: build-lock.sh acquire|release <lockdir> [pid]}"
lockdir="${2:?usage: build-lock.sh acquire|release <lockdir> [pid]}"

# The atomic primitive is bash's own O_EXCL file creation (noclobber), NOT `mkdir`: on the dev
# server `mkdir` is uutils coreutils, and under concurrency it reports success to several callers
# for the same path (12 of 12 in a test) — a mkdir-based lock let four Maven builds run side by
# side there. The lock is held exactly while "$lockdir/pid" exists; the directory is only its
# container and is removed again on release, so older copies of this script (which treat the
# directory itself as the lock) are not blocked forever.
try_create() { # <file> <content> — succeeds for exactly one caller
	( set -o noclobber; echo "$2" > "$1" ) 2>/dev/null
}

case "$cmd" in
	acquire)
		owner_pid="${3:?acquire needs the calling shell pid as third argument}"
		while true; do
			mkdir -p "$lockdir" 2>/dev/null || true
			if try_create "$lockdir/pid" "$owner_pid"; then
				break
			fi
			holder="$(cat "$lockdir/pid" 2>/dev/null || true)"
			stale=""
			if [ -n "$holder" ] && ! kill -0 "$holder" 2>/dev/null; then
				stale="dead"
			elif [ -z "$holder" ] && [ -n "$(find "$lockdir/pid" -mmin +1 2>/dev/null)" ]; then
				# Created but never filled: the owner died between the two steps.
				stale="unowned"
			fi
			if [ -n "$stale" ]; then
				# Only ONE waiter at a time may remove a stale lock, and it looks at the pid again once
				# it is alone: otherwise a second waiter that also saw the dead pid deletes the lock the
				# first one has just taken, and both build side by side.
				if try_create "$lockdir/steal" "$$"; then
					current="$(cat "$lockdir/pid" 2>/dev/null || true)"
					if [ "$current" = "$holder" ]; then
						rm -f "$lockdir/pid"
					fi
					rm -f "$lockdir/steal"
				elif [ -n "$(find "$lockdir/steal" -mmin +1 2>/dev/null)" ]; then
					# A waiter died inside the few lines above; its marker must not block stealing.
					rm -f "$lockdir/steal"
				fi
				continue
			fi
			echo "[build-lock] waiting for build lock ($lockdir, held by PID ${holder:-unknown})"
			sleep 15
		done
		;;
	release)
		# With a pid the lock is only released by its owner. An unconditional removal let every
		# finishing build delete the lock of whoever held it by then, so one double-holding kept
		# reproducing itself until the queue drained. Without a pid: the old behaviour.
		owner_pid="${3:-}"
		if [ -n "$owner_pid" ]; then
			holder="$(cat "$lockdir/pid" 2>/dev/null || true)"
			if [ "$holder" != "$owner_pid" ]; then
				exit 0
			fi
		fi
		rm -f "$lockdir/pid"
		rmdir "$lockdir" 2>/dev/null || true
		;;
	*)
		echo "build-lock.sh: unknown command '$cmd' (expected acquire|release)" >&2
		exit 2
		;;
esac
