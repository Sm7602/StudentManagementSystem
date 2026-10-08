# Deployment and Setup Guide

Tested on Ubuntu 24.04 with MySQL 8.0.46 and OpenJDK 21 (evidence in [`evidence/`](evidence/)); the
full test suite was also run on OpenJDK 17, and the application was started on OpenJDK 11.
Windows (`.bat`) and macOS commands are provided for the same steps.

---

## 1. Install the prerequisites

| Software | Version | Check |
|---|---|---|
| JDK | 17 or newer recommended (code compiled with `--release 11`; the app also runs on 11, the bundled JUnit runner needs 12+) | `javac -version` |
| MySQL Server | 8.0.16 or newer (CHECK constraints are enforced from 8.0.16) | `mysql --version` |
| MySQL client tools `mysql`, `mysqldump` | same as server (installed with it) | only needed for backup / restore |

* **Windows:** MySQL Installer -> "MySQL Server" (+ "MySQL Shell" optional). JDK: Eclipse Temurin
  or Oracle JDK. Make sure `javac` and `java` are on the PATH (`javac -version` in a new
  terminal). The MySQL `bin` folder is usually not on the PATH - see section 4.
* **macOS:** `brew install openjdk@21 mysql` then `brew services start mysql`.
* **Ubuntu/Debian:** `sudo apt install openjdk-21-jdk mysql-server` (`sudo service mysql start`).

No build tool is needed: all libraries are in `lib/`.

## 2. Get the project

Unzip the project and open a terminal **in the project root** (the folder that contains
`README.md`). All commands and relative paths assume this working directory.

## 3. Create the database account (choose one)

**a) Use `root`** (quickest for a local evaluation): nothing to create. Set the password as
described in section 4.

**b) Dedicated account (recommended):** edit the password in `database/create_app_user.sql`, then

```bash
mysql -u root -p < database/create_app_user.sql
```

This creates `sms_user@localhost` with privileges only on `student_management` and
`student_management_test`.

> On Ubuntu the MySQL `root` account authenticates through the operating system (`auth_socket`)
> and cannot log in over JDBC with a password - use option b there.

## 4. Configure the connection

Edit `config/database.properties` (tests: `config/database-test.properties`):

```properties
db.url=jdbc:mysql://localhost:3306/student_management?rewriteBatchedStatements=true
# root or sms_user
db.username=root
# better: leave empty and use the DB_PASSWORD environment variable
db.password=
```

(Comments in `.properties` files must be on their own line - text after a value becomes part of
the value.)

Provide the password through environment variables (they override the file):

```bash
# Linux / macOS
export DB_USERNAME=sms_user
export DB_PASSWORD='your-password'
```

```bat
:: Windows (cmd)
set DB_USERNAME=sms_user
set DB_PASSWORD=your-password
```

If `mysqldump` / `mysql` are not on the PATH (typical on Windows), set their full paths for backup
and restore:

```properties
backup.mysqldumpPath=C:/Program Files/MySQL/MySQL Server 8.0/bin/mysqldump.exe
backup.mysqlPath=C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe
```

## 5. Start the application

```bash
./run.sh          # Linux / macOS  (chmod +x run.sh run-tests.sh once if needed)
run.bat           # Windows
```

The script compiles `src/` into `out/classes` and starts `com.studentmanagement.Main`.

## 6. Initialize the database

Choose **1. Initialize Database** and answer `y` to load the sample data. This creates the
`student_management` database, the three tables with all constraints and indexes, the two stored
procedures and (on an empty database) 10 students, 6 courses and 16 enrollments.

Alternative without the application: `mysql -u root -p < database/install.sql` (run from the
project root).

## 7. Verify the installation

```bash
./run-tests.sh    # Windows: run-tests.bat
```

Expected end of the output:

```
[       112 tests successful      ]
[         0 tests failed          ]
```

The tests create and use the separate database `student_management_test`; the application
database is never touched (the test helper refuses to run against a database whose name does not
end with `_test`).

## 8. Running from an IDE (optional)

IntelliJ IDEA (Eclipse is analogous):

1. *File -> Open* the project folder.
2. Mark `src` as **Sources Root** and `tests` as **Test Sources Root**.
3. *Project Structure -> Modules -> Dependencies*: add all JARs in `lib/` (compile) and
   `lib/test/` (test).
4. Run configuration for `com.studentmanagement.Main`: **working directory = project root**,
   environment variables `DB_USERNAME`, `DB_PASSWORD`.
5. JUnit run configuration: add the VM option `-Ddb.config=config/database-test.properties`.

## 9. Production notes

* **Credentials:** use a dedicated account with privileges only on the application schema and
  pass the password via `DB_PASSWORD` (or a secrets manager); never commit real passwords.
* **Encryption in transit:** for a remote MySQL server add `sslMode=REQUIRED` (or `VERIFY_IDENTITY`
  with a trusted CA) to `db.url`.
* **Pool sizing:** `pool.maxTotal` <= MySQL `max_connections` divided by the number of application
  instances; keep `minIdle <= maxIdle <= maxTotal` (validated at start-up).
* **Backups:** schedule `run.sh --backup` and copy the files off the machine
  (see [BACKUP_AND_RECOVERY.md](BACKUP_AND_RECOVERY.md#7-operational-recommendations-not-automated-by-this-project)).
* **Logs:** `logs/application.log` (appended); rotate or archive it with the usual OS tools.
* **Upgrades:** `schema.sql` uses `CREATE TABLE IF NOT EXISTS` and never alters existing tables;
  structural changes to an existing installation must be applied with explicit `ALTER TABLE`
  scripts after taking a backup.

## 10. Reset / uninstall

```sql
DROP DATABASE student_management;        -- application data
DROP DATABASE student_management_test;   -- created by the tests
DROP USER 'sms_user'@'localhost';        -- if created in step 3b
```

Then delete the project folder (including the generated `out/`, `logs/` and `backups/`).
