#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Compiles and starts the Student Management System (Linux / macOS).
#
#   ./run.sh                       interactive menu
#   ./run.sh --backup              back up the database (needs mysqldump)
#   ./run.sh --restore <file.sql>  restore a backup     (needs mysql client)
#
# Requires JDK 11+ and a running MySQL 8 server configured in
# config/database.properties (or DB_USERNAME / DB_PASSWORD environment variables).
# Extra JVM options can be passed in JAVA_OPTS, e.g.
#   JAVA_OPTS="-Ddb.config=config/other.properties" ./run.sh
# -----------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")"

rm -rf out/classes
mkdir -p out/classes
javac --release 11 -encoding UTF-8 -d out/classes -cp "lib/*" $(find src -name "*.java")

exec java ${JAVA_OPTS:-} -cp "out/classes:lib/*" com.studentmanagement.Main "$@"
