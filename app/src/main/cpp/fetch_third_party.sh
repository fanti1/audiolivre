#!/usr/bin/env bash
# Puts the translation engine's sources in app/src/main/cpp/third_party/slimt, at the exact commits the app is
# built and tested with. Run once after cloning; Gradle compiles them only when this folder exists.
#
#   slimt (David Ventura's fork)  GPL-2.0-or-later  https://github.com/DavidVentura/slimt
#   SentencePiece (browsermt)     Apache-2.0        https://github.com/browsermt/sentencepiece
#   ruy                           Apache-2.0        https://github.com/google/ruy
#   cpuinfo                       BSD-2-Clause      https://github.com/pytorch/cpuinfo
set -euo pipefail

SLIMT_COMMIT=2beb403abfff5b2f4c165a6d51362c973d037e97
SENTENCEPIECE_COMMIT=8cbdf13794284c30877936f91c6f31e2c1d5aef7
RUY_COMMIT=c04e5e52ae6b144f74ac032652e3c538bda15c9b
CPUINFO_COMMIT=082deffc80ce517f81dc2f3aebe6ba671fcd09c9

here="$(cd "$(dirname "$0")" && pwd)"
dest="$here/third_party/slimt"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

fetch() { # repo commit dir
  git -C "$work" init -q "$3"
  git -C "$work/$3" fetch -q --depth 1 "https://github.com/$1.git" "$2"
  git -C "$work/$3" checkout -q FETCH_HEAD
}

fetch DavidVentura/slimt "$SLIMT_COMMIT" slimt
fetch browsermt/sentencepiece "$SENTENCEPIECE_COMMIT" sentencepiece
fetch google/ruy "$RUY_COMMIT" ruy
fetch pytorch/cpuinfo "$CPUINFO_COMMIT" cpuinfo

rm -rf "$work/slimt/3rd-party/sentencepiece" "$work/slimt/3rd-party/ruy"
mv "$work/sentencepiece" "$work/slimt/3rd-party/sentencepiece"
mv "$work/ruy" "$work/slimt/3rd-party/ruy"
rm -rf "$work/slimt/3rd-party/ruy/third_party/cpuinfo"
mv "$work/cpuinfo" "$work/slimt/3rd-party/ruy/third_party/cpuinfo"

# Only what the build uses: no git history, tests, sample data or Python bindings.
cd "$work/slimt"
find . -name .git -prune -exec rm -rf {} +
rm -rf .github 3rd-party/sentencepiece/.github 3rd-party/sentencepiece/data 3rd-party/sentencepiece/python \
  3rd-party/sentencepiece/doc 3rd-party/ruy/third_party/googletest 3rd-party/ruy/third_party/cpuinfo/test \
  3rd-party/ruy/third_party/cpuinfo/bench 3rd-party/ruy/doc 3rd-party/ruy/example bindings/python scripts \
  packaging data .gitmodules
mkdir -p 3rd-party/ruy/third_party/googletest

rm -rf "$dest"
mkdir -p "$(dirname "$dest")"
mv "$work/slimt" "$dest"
echo "slimt sources in $dest ($(du -sh "$dest" | cut -f1))"
