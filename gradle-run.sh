#!/usr/bin/env bash
# Runs Gradle and, if it fails, copies the error lines into the run's summary (visible without opening the full log).
set -o pipefail
./gradlew "$@" 2>&1 | tee build.log
code=$?
if [ $code -ne 0 ]; then
  grep -E "error:|What went wrong|\.java:[0-9]+|\.gradle'? line|Could not|> " build.log | head -25 | while IFS= read -r l; do echo "::error::${l}"; done
fi
exit $code
