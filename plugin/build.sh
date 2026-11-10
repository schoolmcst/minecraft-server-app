#!/usr/bin/env bash
# Builds plugin/XrayWatch.jar. Compiles against stand-ins of the Paper API (plugin/stubs), which are not shipped.
set -euo pipefail
cd "$(dirname "$0")"
B=$(mktemp -d)
javac --release 17 -nowarn -d "$B/stubs" $(find stubs -name '*.java')
javac --release 17 -Xlint:all,-serial,-processing -cp "$B/stubs" -d "$B/classes" $(find src -name '*.java')
cp src/plugin.yml "$B/classes/"
jar cf XrayWatch.jar -C "$B/classes" .
rm -rf "$B"
echo "built plugin/XrayWatch.jar"
