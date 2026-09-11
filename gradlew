#!/bin/sh
if [ -f "gradle/wrapper/gradle-wrapper.jar" ]; then
  exec java -jar gradle/wrapper/gradle-wrapper.jar "$@"
else
  exec gradle "$@"
fi
