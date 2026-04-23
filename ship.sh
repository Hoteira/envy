#!/usr/bin/bash

./gradlew clean buildPlugin
./gradlew test
./gradlew verifyPlugin