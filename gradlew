#!/usr/bin/env sh

DIR="$(cd "$(dirname "$0")" && pwd)"
GRADLE_WRAPPER_JAR="$DIR/gradle/wrapper/gradle-wrapper.jar"

exec java -jar "$GRADLE_WRAPPER_JAR" "$@"
