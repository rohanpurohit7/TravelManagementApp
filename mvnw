#!/bin/sh
# Maven Wrapper
MVNW_REPOURL=""
MVNW_VERBOSE=${MVNW_VERBOSE:-false}
BASEDIR=$(cd "$(dirname "$0")"; pwd -P)
WRAPPER_JAR="$BASEDIR/.mvn/wrapper/maven-wrapper.jar"
WRAPPER_LAUNCHER=org.apache.maven.wrapper.MavenWrapperMain
JAVA_EXE=java
if [ -n "$JAVA_HOME" ]; then
  JAVA_EXE="$JAVA_HOME/bin/java"
fi
if [ ! -f "$WRAPPER_JAR" ]; then
  echo "Maven wrapper jar not found: $WRAPPER_JAR" >&2
  exit 1
fi
exec "$JAVA_EXE" -classpath "$WRAPPER_JAR" -Dmaven.multiModuleProjectDirectory="$BASEDIR" "$WRAPPER_LAUNCHER" "$@"
