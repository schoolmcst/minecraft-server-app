#!/bin/bash
# Mac: double-click this file. Linux: run ./start-server.command in a terminal.
# Add --update to download the newest Paper/Geyser/Floodgate first.
cd "$(dirname "$0")" || exit 1
clear

# Memory for the server. 4G suits 1-5 players; use 3G on an 8 GB machine.
RAM=4G

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
    echo "  Setting up Java for the server (one time only, about 50 MB)..."
    echo
    rm -rf runtime-download && mkdir runtime-download
    if curl -fL --progress-bar -o runtime-download/java.tar.gz \
            "https://api.adoptium.net/v3/binary/latest/25/ga/$OS/$ARCH/jre/hotspot/normal/eclipse" \
       && tar -xzf runtime-download/java.tar.gz -C runtime-download; then
        mv runtime-download/jdk* runtime
    fi
    rm -rf runtime-download
    JAVA=$(find_java)
    clear
fi

if [ -z "$JAVA" ]; then
    echo
    echo "  ! Couldn't set up Java automatically. Check your internet connection and try again."
    echo "    Or download the Java 25 JRE from https://adoptium.net/temurin/releases/?version=25,"
    echo "    unpack it here and rename the folder to  runtime"
else
    "$JAVA" -jar launcher.jar "$RAM" "$@"
fi
echo
read -r -p "  Press Enter to close this window." _
