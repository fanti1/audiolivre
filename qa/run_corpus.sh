#!/usr/bin/env bash
# Extracts the test corpus on the connected device and scores it against the text the PDFs were built from.
#
#     bash qa/run_corpus.sh [--verbose]
#
# Git Bash rewrites arguments that look like Unix paths, which silently turns an adb device path into
# "C:/Program Files/Git/sdcard/..." and leaves the previous result file in place — so the score looks
# unchanged however much the parser improved. MSYS_NO_PATHCONV stops that.
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}"
export PATH="$PATH:$SDK/platform-tools"
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${CORPUS_OUT:-$HOME/qa-models}/corpus_extraction.jsonl"
REMOTE="/sdcard/Android/data/io.github.fanti1.audiolivre/files/corpus_extraction.jsonl"

cd "$PROJECT"
./gradlew -q installDebug installDebugAndroidTest
MSYS_NO_PATHCONV=1 adb shell rm -f "$REMOTE" || true
adb shell am instrument -w -e qaDump true -e class io.github.fanti1.audiolivre.CorpusDumpTest \
    io.github.fanti1.audiolivre.test/androidx.test.runner.AndroidJUnitRunner | tail -3
rm -f "$OUT"
MSYS_NO_PATHCONV=1 adb pull "$REMOTE" "$(cygpath -w "$OUT" 2>/dev/null || echo "$OUT")"
test -s "$OUT" || { echo "extraction did not reach $OUT"; exit 1; }
python qa/check_extraction.py "$(cygpath -w "$OUT" 2>/dev/null || echo "$OUT")" "$@"
