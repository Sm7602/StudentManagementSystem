#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Compiles the application and the JUnit 5 tests, then runs every test against
# the TEST database configured in config/database-test.properties
# (student_management_test - created automatically, never the real database).
#
#   ./run-tests.sh
#
# Credentials: db.username / db.password in config/database-test.properties or
# the DB_USERNAME / DB_PASSWORD environment variables.
# XML reports are written to out/test-reports/.
# -----------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")"

rm -rf out/classes out/test-classes out/test-reports
mkdir -p out/classes out/test-classes
javac --release 11 -encoding UTF-8 -d out/classes -cp "lib/*" $(find src -name "*.java")
javac --release 11 -encoding UTF-8 -d out/test-classes -cp "out/classes:lib/*:lib/test/*" $(find tests -name "*.java")

java -Ddb.config=config/database-test.properties \
     -cp "out/classes:out/test-classes:lib/*:lib/test/*" \
     org.junit.platform.console.ConsoleLauncher execute \
     --scan-class-path out/test-classes \
     --include-engine junit-jupiter \
     --details tree --details-theme ascii --disable-banner \
     --reports-dir out/test-reports \
     "$@"
