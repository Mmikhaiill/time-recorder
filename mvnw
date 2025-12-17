#!/bin/sh
# Maven Wrapper script

set -e

MAVEN_WRAPPER_JAR=".mvn/wrapper/maven-wrapper.jar"
MAVEN_WRAPPER_PROPERTIES=".mvn/wrapper/maven-wrapper.properties"

# Download maven wrapper jar if not present
if [ ! -f "$MAVEN_WRAPPER_JAR" ]; then
    echo "Downloading Maven Wrapper..."
    if command -v wget > /dev/null; then
        wget -q -O "$MAVEN_WRAPPER_JAR" "https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.2.0/maven-wrapper-3.2.0.jar"
    elif command -v curl > /dev/null; then
        curl -s -o "$MAVEN_WRAPPER_JAR" "https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.2.0/maven-wrapper-3.2.0.jar"
    else
        echo "Error: wget or curl required"
        exit 1
    fi
fi

# Check for java
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD=java
fi

exec "$JAVACMD" \
    -jar "$MAVEN_WRAPPER_JAR" \
    "$@"
