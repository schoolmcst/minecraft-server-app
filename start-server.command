#!/bin/bash
# Opens the Minecraft Server app.
# Mac: double-click this file (the first time: right-click > Open > Open). Linux: run ./start-server.command
# The first time, it downloads Java for the app (about 50 MB) into the "runtime" folder.
cd "$(dirname "$0")" || exit 1

find_java() {
    for j in runtime/Contents/Home/bin/java runtime/bin/java; do
        if [ -x "$j" ]; then echo "$j"; return; fi
    done
}

JAVA=$(find_java)
if [ -z "$JAVA" ]; then
    case "$(uname -s)" in Darwin) OS=mac ;; *) OS=linux ;; esac
    case "$(uname -m)" in arm64|aarch64) ARCH=aarch64 ;; *) ARCH=x64 ;; esac
    echo
    echo "  Setting up Java for the server app (one time only, about 50 MB)..."
    echo
    rm -rf runtime-download && mkdir runtime-download
    if curl -fL --progress-bar -o runtime-download/java.tar.gz \
            "https://api.adoptium.net/v3/binary/latest/25/ga/$OS/$ARCH/jre/hotspot/normal/eclipse" \
       && tar -xzf runtime-download/java.tar.gz -C runtime-download; then
        mv runtime-download/jdk* runtime
    fi
    rm -rf runtime-download
    JAVA=$(find_java)
fi

if [ -z "$JAVA" ]; then
    echo
    echo "  ! Couldn't set up Java automatically. Check your internet connection and try again."
    echo "    Or download the Java 25 JRE from https://adoptium.net/temurin/releases/?version=25,"
    echo "    unpack it here and rename the folder to  runtime"
    read -r -p "  Press Enter to close." _
    exit 1
fi

# Start the app in the background so this terminal window can close.
nohup "$JAVA" -jar launcher.jar >/dev/null 2>&1 &
disown 2>/dev/null
if [ "$(uname -s)" = "Darwin" ]; then
    osascript -e 'tell application "Terminal" to close (every window whose name contains "start-server")' >/dev/null 2>&1 &
fi
exit 0
