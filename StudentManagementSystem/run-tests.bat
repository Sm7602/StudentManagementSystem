@echo off
REM ---------------------------------------------------------------------------
REM Compiles the application and the JUnit 5 tests, then runs every test against
REM the TEST database configured in config\database-test.properties
REM (student_management_test - created automatically, never the real database).
REM
REM Credentials: db.username / db.password in config\database-test.properties or
REM the DB_USERNAME / DB_PASSWORD environment variables.
REM XML reports are written to out\test-reports\.
REM ---------------------------------------------------------------------------
setlocal
cd /d "%~dp0"

if exist out\classes rmdir /s /q out\classes
if exist out\test-classes rmdir /s /q out\test-classes
if exist out\test-reports rmdir /s /q out\test-reports
mkdir out\classes
mkdir out\test-classes

javac --release 11 -encoding UTF-8 -d out\classes -cp "lib\*" ^
  src\com\studentmanagement\*.java ^
  src\com\studentmanagement\config\*.java ^
  src\com\studentmanagement\model\*.java ^
  src\com\studentmanagement\dao\*.java ^
  src\com\studentmanagement\service\*.java ^
  src\com\studentmanagement\util\*.java
if errorlevel 1 exit /b 1

javac --release 11 -encoding UTF-8 -d out\test-classes -cp "out\classes;lib\*;lib\test\*" ^
  tests\com\studentmanagement\*.java ^
  tests\com\studentmanagement\config\*.java ^
  tests\com\studentmanagement\dao\*.java ^
  tests\com\studentmanagement\service\*.java ^
  tests\com\studentmanagement\util\*.java
if errorlevel 1 exit /b 1

java -Ddb.config=config/database-test.properties ^
     -cp "out\classes;out\test-classes;lib\*;lib\test\*" ^
     org.junit.platform.console.ConsoleLauncher execute ^
     --scan-class-path out\test-classes ^
     --include-engine junit-jupiter ^
     --details tree --details-theme ascii --disable-banner ^
     --reports-dir out\test-reports ^
     %*
exit /b %errorlevel%
