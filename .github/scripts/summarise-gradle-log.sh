#!/usr/bin/env bash
#
# Put the readable part of a Gradle failure into the GitHub job summary.
#
# Why this exists: this project is written on a machine with no JDK, no Gradle
# and no Android SDK, so CI is the only compiler it has. A red tick with the
# detail locked inside an Actions log is close to useless there — fetching a raw
# job log needs an authenticated client, while a job summary is readable through
# the public checks API. So the compiler errors are copied somewhere they can
# actually be read, and the artifact upload stays as the full-fidelity fallback.
#
# Usage: summarise-gradle-log.sh <logfile> <label>

set -uo pipefail

log="${1:?usage: summarise-gradle-log.sh <logfile> <label>}"
label="${2:-build}"
summary="${GITHUB_STEP_SUMMARY:-/dev/stdout}"

{
  echo "## ${label} failed"
  echo
} >>"$summary"

if [ ! -s "$log" ]; then
  echo "No log was captured. The step probably failed before Gradle started." >>"$summary"
  exit 0
fi

emit_section() {
  local title="$1"
  local body="$2"
  [ -z "$body" ] && return 0
  {
    echo "### ${title}"
    echo '```'
    printf '%s\n' "$body"
    echo '```'
    echo
  } >>"$summary"
}

# Kotlin reports errors as "e: file:line:col message"; javac, KSP and Room use
# "error:"; Gradle marks the task itself. Between them these three patterns catch
# every compile failure this build can produce.
emit_section "Compiler errors" \
  "$(grep -E '^e: |error: |^> Task .* FAILED' "$log" | head -n 150)"

# Test failures, when the tests compiled but did not pass. The build file turns
# on FULL exception format so the expected/actual pair lands here too.
emit_section "Test failures" \
  "$(grep -E 'FAILED$|^\s+[A-Za-z0-9_.]+ > |expected:|actual:|AssertionError' "$log" | head -n 120)"

emit_section "What went wrong" \
  "$(sed -n '/^\* What went wrong:/,/^\* Try:/p' "$log" | head -n 60)"

{
  echo "<details><summary>Last 250 lines of the build log</summary>"
  echo
  echo '```'
  tail -n 250 "$log"
  echo '```'
  echo
  echo "</details>"
} >>"$summary"
