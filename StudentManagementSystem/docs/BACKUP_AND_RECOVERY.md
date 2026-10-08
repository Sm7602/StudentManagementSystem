# Backup and Recovery

Implementation: `DatabaseUtils.backupDatabase()` / `DatabaseUtils.restoreDatabase()`, started with
the command-line options `--backup` and `--restore <file>` of the application
(the interactive menu keeps exactly the 10 options of the specification).
Verified by `BackupRecoveryTest` and the captured run in
[`evidence/backup-restore-session.txt`](evidence/backup-restore-session.txt).

---

## 1. Strategy

| Item | Choice |
|---|---|
| Type | Logical backup (SQL statements) made with **mysqldump**, the standard MySQL tool |
| Content | `CREATE DATABASE IF NOT EXISTS`, all table definitions (with constraints and indexes), all rows, both stored procedures |
| Consistency | `--single-transaction`: one consistent InnoDB snapshot, no table locks - the application can keep working during the backup |
| Recovery | the `mysql` client replays the file: database recreated if missing, tables dropped and recreated, data and procedures restored |
| Credentials | written to a temporary option file (`[client]` section, owner-only permissions on Linux/macOS), passed with `--defaults-extra-file`, deleted afterwards - the password never appears on the command line or in the process list |
| Failure handling | non-zero exit code -> `IOException` with mysqldump's / mysql's error text; a partial backup file is deleted |

## 2. Prerequisites

* MySQL client tools `mysqldump` and `mysql` (installed with MySQL Server; on Windows they are in
  `C:\Program Files\MySQL\MySQL Server 8.x\bin`, which is usually **not** on the PATH).
  If they are not on the PATH, set the full paths in `config/database.properties`:

  ```properties
  backup.mysqldumpPath=C:/Program Files/MySQL/MySQL Server 8.0/bin/mysqldump.exe
  backup.mysqlPath=C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe
  ```

* The configured MySQL account needs privileges on the application database: for backups
  `SELECT`, `TRIGGER` and the right to read the procedure definitions (it is their DEFINER when
  option 1 created them, or it has `SHOW_ROUTINE`); for restores `CREATE`, `DROP`, `INSERT`,
  `CREATE ROUTINE` and `ALTER ROUTINE`. `root`, or the `sms_user` account from
  `database/create_app_user.sql` (ALL on the schema), has all of them; the recorded runs used
  `sms_user`.
* `--no-tablespaces` is used, so the global `PROCESS` privilege is not required.

## 3. Creating a backup

```bash
./run.sh --backup          # Linux / macOS
run.bat --backup           # Windows
```

Output (from the captured run):

```
Creating backup of database 'student_management' with mysqldump ...
[OK] Backup created: backups/student_management_20261009_014449_342.sql (11,629 bytes) - tables, data and stored procedures
```

* **Location:** `backups/` under the project root (property `backup.directory`).
* **File name:** `<database>_<yyyyMMdd_HHmmss_SSS>.sql` - one new file per run, never overwritten.
* **Command executed** (equivalent):

  ```bash
  mysqldump --defaults-extra-file=<temp file> --single-transaction --routines --triggers \
            --no-tablespaces --databases student_management > backups/student_management_<timestamp>.sql
  ```

## 4. Recovering from a backup

```bash
./run.sh --restore backups/student_management_20261009_014449_342.sql
```

The command shows a warning and only proceeds after `YES` is typed:

```
WARNING: this replaces all tables, data and stored procedures of database 'student_management' with the contents of backups/student_management_20261009_014449_342.sql
Type YES to continue: YES
[OK] Database restored from backups/student_management_20261009_014449_342.sql - now 18 students, 6 courses, 19 enrollments
```

After the restore the application reads the record counts through the restored stored procedure
`GetDatabaseStatistics`, which verifies that tables, data and procedures are back.
`./run.sh --restore` without a file lists the available backups.

Equivalent manual command:

```bash
mysql -u <user> -p < backups/student_management_<timestamp>.sql
```

## 5. Verified recovery scenario

Captured in [`evidence/backup-restore-session.txt`](evidence/backup-restore-session.txt) and automated
in `BackupRecoveryTest.backupThenTotalLossThenRestoreRecoversDataAndProcedures`:

1. database with data (18 students, 19 enrollments);
2. `--backup`;
3. **disaster**: all three tables and both stored procedures are dropped;
4. `--restore <file>`;
5. tables, rows (same primary keys), and procedures are back; `CALL GetStudentsByDepartment('Physics')`
   works again.

The test additionally checks that text with quotes (`O'Brien`) survives, that a missing backup
file is rejected, and that a corrupt file makes the restore fail with mysql's error message
while the existing data stays untouched.

## 6. Manual backup without the application

```bash
mysqldump -u root -p --single-transaction --routines --triggers --no-tablespaces \
          --databases student_management > student_management_backup.sql
mysql -u root -p < student_management_backup.sql
```

## 7. Operational recommendations (not automated by this project)

* Schedule `run.sh --backup` daily with cron (Linux/macOS) or Task Scheduler (Windows), e.g.
  `0 2 * * * cd /opt/student-management && DB_PASSWORD=... ./run.sh --backup`.
* Copy backup files to a different machine or cloud storage; a backup on the same disk does not
  protect against disk failure.
* Keep several generations (e.g. 7 daily, 4 weekly) and delete older files.
* Test a restore regularly on a separate server or database - the procedure above takes seconds.
* For point-in-time recovery between backups, enable MySQL binary logging and replay the binlogs
  after restoring the last dump (`mysqlbinlog`); this is a server setting outside the application.

## 8. Troubleshooting

| Message | Cause / fix |
|---|---|
| `Cannot run 'mysqldump'. Install the MySQL client tools or set backup.mysqldumpPath ...` | Tool not on the PATH - set the full path (section 2) |
| `mysqldump failed with exit code 2: ... Access denied ...` | Wrong credentials - check `db.username` / `DB_PASSWORD` |
| `mysqldump failed with exit code 2: mysqldump: sms_user has insufficient privileges to SHOW CREATE PROCEDURE ...` | The procedures were created by another account (e.g. `root` via `install.sql`). Run option 1 once with the application account (it recreates them with that account as DEFINER) or grant `SHOW_ROUTINE` |
| `mysql failed with exit code 1: ERROR 1227 (42000) ... Access denied; you need (at least one of) the SYSTEM_USER privilege(s)` (or `SET_USER_ID`) | The backup's procedures have a different DEFINER (e.g. `root`) than the restoring account - restore as that account / `root`, or back up and restore with the same account |
| `Backup file not found: ...` | Check the path; `./run.sh --restore` lists the available backups |
