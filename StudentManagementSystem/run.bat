@echo off
REM ---------------------------------------------------------------------------
REM Compiles and starts the Student Management System (Windows).
REM
REM   run.bat                        interactive menu
REM   run.bat --backup               back up the database (needs mysqldump)
REM   run.bat --restore file.sql     restore a backup     (needs mysql client)
REM
REM Requires JDK 11+ and a running MySQL 8 server configured in
REM config\database.properties (or DB_USERNAME / DB_PASSWORD environment variables).
REM Extra JVM options can be passed in JAVA_OPTS, e.g.
REM   set JAVA_OPTS=-Ddb.config=config/other.properties
REM ---------------------------------------------------------------------------
setlocal
cd /d "%~dp0"

if exist out\classes rmdir /s /q out\classes
mkdir out\classes

javac --release 11 -encoding UTF-8 -d out\classes -cp "lib\*" ^
  src\com\studentmanagement\*.java ^
  src\com\studentmanagement\config\*.java ^
  src\com\studentmanagement\model\*.java ^
  src\com\studentmanagement\dao\*.java ^
  src\com\studentmanagement\service\*.java ^
  src\com\studentmanagement\util\*.java
if errorlevel 1 exit /b 1

java %JAVA_OPTS% -cp "out\classes;lib\*" com.studentmanagement.Main %*
exit /b %errorlevel%
