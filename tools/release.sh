#!/usr/bin/env bash
# Publishes a new app version. Every installed app picks it up the next time it opens
# (and within a few hours if it's already open).
#
#   tools/release.sh 3.3 "Fixed X" "Added Y"
#   git add -A && git commit -m "3.3: ..." && git push
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION="${1:?usage: tools/release.sh <version> \"note\" ...}"; shift
[ $# -gt 0 ] || { echo "add at least one note"; exit 1; }

sed -i -E "s/static final String VERSION = \"[^\"]+\";/static final String VERSION = \"$VERSION\";/" src/Kit.java
BUILD=$(mktemp -d)
javac --release 21 -Xlint:all,-serial -d "$BUILD/classes" src/*.java
printf 'Main-Class: ServerLauncher\nImplementation-Version: %s\n' "$VERSION" > "$BUILD/manifest.txt"
jar cfm launcher.jar "$BUILD/manifest.txt" -C "$BUILD/classes" .
rm -rf "$BUILD"

mkdir -p release
cp launcher.jar release/app.jar
SHA=$(sha256sum release/app.jar | cut -d' ' -f1)
python3 - "$VERSION" "$SHA" "$@" > release/version.json <<'PY'
import json, sys
v, sha, notes = sys.argv[1], sys.argv[2], sys.argv[3:]
print(json.dumps({"version": v, "file": "app.jar", "sha256": sha, "minJava": 21, "notes": notes}, indent=2))
PY
echo "Built $VERSION ($SHA). Now commit and push."
