#!/usr/bin/env python3
"""Filter Maven test and build output to be token-thrifty for AI coding agents.

Preserves essential summaries, errors, failure stacks, compilation errors, and
linters, while discarding boilerplate progress, downloads, and redundant logs.
Stacktraces compress external JVM/framework internals to save context tokens.
"""
import re
import sys


def main():
    # Regular expressions for lines we always care about
    re_test_run = re.compile(r"^\[INFO\] Running (.*)$")
    re_test_result = re.compile(r"^\[(INFO|WARNING|ERROR)\] Tests run:.*$")
    re_build_status = re.compile(r"^\[INFO\] BUILD (SUCCESS|FAILURE)$")
    re_total_time = re.compile(r"^\[INFO\] Total time:.*$")
    re_reactor_summary = re.compile(
        r"^\[INFO\] (Reactor Summary|.*SUCCESS \[|.*FAILURE \[).*$"
    )

    # Benign boilerplate lines to discard
    re_ignore = re.compile(
        r"(?:Re-run Maven using the -X switch|"
        r"To see the full stack trace of the errors|"
        r"For more information about the errors and possible solutions|"
        r"\[ERROR\]\s*-> \[Help 1\]|"
        r"^\[ERROR\]\s*$)"
    )

    # Error & failure markers
    re_error_marker = re.compile(
        r"(?:\[ERROR\]|"
        r"Failed to execute goal|"
        r"BUILD FAILURE|"
        r"FAILURE \[|"
        r"Exception:|"
        r"Error:|"
        r"\.kt:\d+:\d+:|"
        r"\.java:\d+:\d+:|"
        r"weighted issues|"
        r"\(ktlint-check\) @|"
        r"\(detekt-check\) @|"
        r"Failures: \d+|"
        r"Errors: \d+)"
    )

    # Noise stack lines from external framework internals
    re_framework_stack = re.compile(
        r"^\s+at (?:org\.junit|org\.apache\.maven|io\.quarkus\."
        r"|jdk\.internal|java\.lang\.reflect|org\.mockito\."
        r"|org\.jboss|com\.vaadin\.flow\.internal)"
    )

    in_failure_block = False
    failure_block_lines = 0
    omitted_stack_lines = 0

    def flush_omitted():
        nonlocal omitted_stack_lines
        if omitted_stack_lines > 0:
            print(f"    ... {omitted_stack_lines} framework frames omitted ...")
            omitted_stack_lines = 0

    for raw_line in sys.stdin:
        line = raw_line.rstrip("\r\n")

        if not line:
            continue

        if re_ignore.search(line):
            continue

        # Check for test run & result lines
        if re_test_run.search(line):
            flush_omitted()
            print(line)
            in_failure_block = False
            continue

        if re_test_result.search(line):
            flush_omitted()
            print(line)
            in_failure_block = False
            continue

        if (
            re_build_status.search(line)
            or re_total_time.search(line)
            or re_reactor_summary.search(line)
        ):
            flush_omitted()
            print(line)
            in_failure_block = False
            continue

        # Check for start/continuation of failure/error block
        if re_error_marker.search(line):
            flush_omitted()
            print(line)
            in_failure_block = True
            failure_block_lines = 0
            continue

        # Inside an error / failure block
        if in_failure_block:
            if line.startswith("[INFO]") or line.startswith("---"):
                flush_omitted()
                in_failure_block = False
            else:
                failure_block_lines += 1
                if failure_block_lines <= 40:
                    if re_framework_stack.search(line):
                        omitted_stack_lines += 1
                    else:
                        flush_omitted()
                        print(line)

    flush_omitted()


if __name__ == "__main__":
    main()
