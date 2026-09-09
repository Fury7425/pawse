#!/usr/bin/env bash
#
# Make a Gradle failure readable from outside the runner.
#
# Why this exists: this project is written on a machine with no JDK, no Gradle and
# no Android SDK, so CI is the only compiler it has. A red tick whose detail is
# locked inside the Actions log is close to useless there — fetching a raw job log
# needs an authenticated client, and a job summary turns out not to be exposed by
# the checks API either. Workflow annotations are, so every compiler error is
# emitted as one, and the same text is written to the job summary for a human
# reading the run in a browser. The uploaded log artifact stays as the
# full-fidelity fallback.
#
# Usage: summarise-gradle-log.sh <logfile> <label>

set -uo pipefail

log="${1:?usage: summarise-gradle-log.sh <logfile> <label>}"
label="${2:-build}"
summary="${GITHUB_STEP_SUMMARY:-/dev/null}"

echo "## ${label} failed" >>"$summary"
echo >>"$summary"

if [ ! -s "$log" ]; then
  echo "::error title=${label}::No Gradle output was captured; the step failed before Gradle started."
  echo "No log was captured." >>"$summary"
  exit 0
fi

# Kotlin reports "e: file:line:col message"; javac, KSP and Room use "error:";
# Gradle marks the failing task and explains itself under "What went wrong".
# Between them these catch every failure this build can produce.
errors="$(
  {
    grep -E '^e: |^w: .*error|error: |^> Task .* FAILED' "$log"
    sed -n '/^\* What went wrong:/,/^\* Try:/p' "$log"
    grep -E 'FAILED$|expected:|actual:|AssertionError|Caused by:' "$log"
  } | grep -v '^\* Try:$' | awk '!seen[$0]++' | head -n 60
)"

if [ -n "$errors" ]; then
  {
    echo '```'
    printf '%s\n' "$errors"
    echo '```'
    echo
  } >>"$summary"

  # One annotation per line. Annotations are the only part of a run the public
  # checks API returns without a token, so this is the channel that actually
  # carries the diagnosis off the runner.
  n=0
  while IFS= read -r line; do
    [ -z "$line" ] && continue
    n=$((n + 1))
    [ "$n" -gt 40 ] && break
    line="${line//$'\r'/}"
    line="${line//'%'/%25}"
    echo "::error title=${label}::${line}"
  done <<<"$errors"
else
  echo "::error title=${label}::Gradle failed with no recognisable error line; see the uploaded log."
fi

{
  echo "<details><summary>Last 250 lines of the build log</summary>"
  echo
  echo '```'
  tail -n 250 "$log"
  echo '```'
  echo
  echo "</details>"
} >>"$summary"
