# Testing and Testing Evidence

**Result of the recorded run: 112 tests, 112 passed, 0 failed, 0 skipped**
([`evidence/test-run.txt`](evidence/test-run.txt), [`evidence/junit-report.xml`](evidence/junit-report.xml)).

---

## 1. Approach

* **Real database, no mocks.** The DAO, service, report, backup and menu tests run against a real
  MySQL server, because the behaviour under test (constraints, transactions, batches, stored
  procedures, indexes) lives in MySQL. Only `QueryBuilderTest` and the parser/error-message
  tests in `DatabaseUtilsTest` run without the database.
* **Separate test database.** `run-tests` starts the JVM with
  `-Ddb.config=config/database-test.properties`, i.e. database `student_management_test`, which
  is created automatically. `TestDatabase` refuses to run if the configured name does not end
  with `_test`, so application data cannot be damaged.
* **Independent tests.** Each test empties the tables (`TRUNCATE`, so ids restart at 1) and
  creates its own data.
* **Leak detection.** `StudentDAOTest`, `CourseDAOTest`, `EnrollmentDAOTest` and
  `StudentServiceTest` assert after every test that no pooled connection is still borrowed;
  `MainMenuTest` checks it after a complete menu session.
* **Framework:** JUnit 5 (Jupiter 5.10.1) run by the JUnit console launcher - no build tool needed.

## 2. How to run

```bash
./run-tests.sh            # Linux / macOS
run-tests.bat             # Windows
```

Prerequisites: JDK 17 or newer (the bundled JUnit runner needs Java 12+; the suite was run on
JDK 17 and 21), MySQL running, credentials in `config/database-test.properties` or in
`DB_USERNAME` / `DB_PASSWORD`. Output: a test tree in the console, JUnit XML in
`out/test-reports/TEST-junit-jupiter.xml`, and the application log written during the tests in
`out/test-reports/application-test.log` (several tests provoke errors on purpose; their stack
traces go there instead of the console). `BackupRecoveryTest` is skipped (not failed) if
`mysqldump`/`mysql` cannot be started.

## 3. Test inventory

| Test class | Tests | What is verified |
|---|---:|---|
| `config/DatabaseConfigTest` | 5 | pool settings read from the properties file (minIdle 5, maxIdle 10, maxTotal 20, maxOpenPreparedStatements 100); URL parsing; pooled connection valid and on the test DB; closing returns connections to the pool; the same MySQL session is reused |
| `config/DatabaseInitializerTest` | 3 | empty database -> 3 tables, 7 indexes, 4 CHECK constraints, CASCADE/RESTRICT foreign keys, 2 procedures; re-running keeps data; sample data loaded only into an empty database |
| `dao/StudentDAOTest` | 17 | create (generated id, every field incl. timestamps, NULL GPA), read by id / all, search (partial, case-insensitive, full name, e-mail, department + min GPA, all three combined), **SQL-injection and wildcard input**, update, delete, unique e-mail (1062), CHECK on GPA (3819), batch insert with id write-back, chunked batch (1,037 rows), **batch rollback** after a failure in the second chunk, stored procedure `GetStudentsByDepartment`, department list |
| `dao/CourseDAOTest` | 8 | full CRUD, lookup by code, ordering, duplicate code (1062), credits CHECK (3819), delete blocked while students are enrolled (1451) |
| `dao/EnrollmentDAOTest` | 11 | full CRUD by composite key, lists by student / course, duplicate enrollment (1062), unknown student (1452), grade CHECK (3819), ON DELETE CASCADE, batch committed by the caller, **rollback of an earlier batch in the same transaction**, batch refused outside a transaction |
| `dao/BatchPerformanceTest` | 1 | 1,000 row-by-row inserts vs. one JDBC batch: both store 1,000 rows, the batch is faster; prints the measured times |
| `service/StudentServiceTest` | 25 | normalisation (trim, lower-case e-mail, default enrollment date); 12 invalid-input cases rejected before any SQL; all problems reported together; update validation; search criteria; **transaction commit** (student + enrollments); **transaction rollback** (unknown course - proven by the consumed AUTO_INCREMENT value; duplicate e-mail); CSV import: success, invalid rows with line numbers, rollback on a database error, missing header; batch enrollment commit and all-or-nothing |
| `service/ReportServiceTest` | 9 | totals from the OUT parameters of `GetDatabaseStatistics`; NULL average on an empty DB; per-department count and average (rounding); per-course counts including zero; pool status; index list from information_schema; **EXPLAIN uses idx_students_department_gpa (`ref`)**; complete report; server info |
| `util/QueryBuilderTest` | 14 | SQL text and parameter order, optional conditions, user input never in the SQL text, invalid identifiers rejected (6 cases), placeholder/value mismatch, separators/comments rejected, LIKE escaping, read-only parameter list |
| `util/DatabaseUtilsTest` | 12 | script parser (comments, `DELIMITER`, quotes, unterminated quote, the project's own scripts); transaction template commit / rollback on SQLException / rollback on RuntimeException / auto-commit restored; parameter binding with NULL; friendly error messages and unwrapping of pool exceptions |
| `util/BackupRecoveryTest` | 3 | backup -> drop all tables and procedures -> restore -> data (same ids, quotes intact) and procedures back; missing file rejected; corrupt file reported, data kept |
| `MainMenuTest` | 4 | **end-to-end through the console menu**: all 10 options in one session with output and database state checked; invalid input (menu choice, empty/invalid name, e-mail, date format, future date, GPA range/format, enrollment date, unknown course -> rollback, unknown id, missing CSV); database error message and menu keeps running, end of input; update keeping values with Enter |
| **Total** | **112** | |

## 4. Requirement coverage

| Requirement | Tests |
|---|---|
| Database connection / pooling | `DatabaseConfigTest` (5) |
| Create student | `StudentDAOTest.addStudentStoresEveryFieldAndReturnsTheGeneratedId`, `StudentServiceTest.addStudentNormalisesInputAndReturnsTheStoredRecord`, `MainMenuTest` |
| Retrieve student / all students | `StudentDAOTest.getStudentById...`, `...getAllStudentsReturnsEveryStudentOrderedById`, `MainMenuTest` |
| Search students | `StudentDAOTest.search*` (3), `StudentServiceTest.searchNeedsAtLeastOneCriterionAndAValidGpa`, `MainMenuTest` |
| Update student | `StudentDAOTest.update*` (2), `StudentServiceTest.updateStudentValidatesAndPersistsChanges`, `MainMenuTest.updateKeepsValuesWhenEnterIsPressed` |
| Delete student | `StudentDAOTest.deleteStudentRemovesTheRow`, `EnrollmentDAOTest.deletingAStudentDeletesTheirEnrollments`, `MainMenuTest` |
| Transaction success | `StudentServiceTest.registerWithCoursesCommitsTheStudentAndAllEnrollmentsTogether`, `DatabaseUtilsTest.transactionIsCommittedWhenTheWorkSucceeds`, `EnrollmentDAOTest.batchInsideATransactionIsCommittedByTheCaller` |
| Transaction rollback | `StudentServiceTest.registerWithCoursesRollsBack...`, `...FailsAtomicallyOnADuplicateEmail`, `...csvImportIsRolledBack...`, `...batchEnrollmentIsAllOrNothing`, `DatabaseUtilsTest.transactionIsRolledBack*` (2), `EnrollmentDAOTest.aFailingBatchRollsBack...` |
| Batch operations | `StudentDAOTest.batch*`, `largeBatches...`, `EnrollmentDAOTest.batch*`, `BatchPerformanceTest`, `StudentServiceTest.csv*` / `batchEnrollment*` |
| Stored procedures | `StudentDAOTest.storedProcedureReturns...`, `ReportServiceTest.totalsAreReadFromTheStoredProcedureOutParameters`, `averageGpaIsNullForAnEmptyDatabase` |
| Backup and recovery | `BackupRecoveryTest` (3) |
| Validation / error cases | `StudentServiceTest.invalidInputIsRejectedBeforeAnySqlIsExecuted` (12 cases), constraint tests (1062, 1451, 1452, 3819), `MainMenuTest` (2 error scenarios), `DatabaseUtilsTest.mySqlErrorCodes...` |
| Indexes / performance | `ReportServiceTest.departmentLookupUsesTheCompositeIndex`, `BatchPerformanceTest` |
| SQL-injection safety | `StudentDAOTest.searchTreatsWildcardsAndSqlInjectionAttemptsAsPlainText`, `QueryBuilderTest` |

## 5. Evidence files

All files in [`docs/evidence/`](evidence/) are unedited output of real runs; each starts with a
header naming the capture time and the software versions.

| File | Content |
|---|---|
| `test-run.txt` | console output of `./run-tests.sh` (test tree, summary, batch measurement) |
| `junit-report.xml` | JUnit XML report of the same run (112 testcases, 0 failures) |
| `demo-input.txt` | the keyboard input used for the demo session |
| `demo-session.txt` | complete console session starting on a server **without** the database: initialization, every menu option, validation errors, a rolled-back registration, a duplicate e-mail, a committed and a rolled-back batch import, batch enrollment, stored procedure, statistics |
| `backup-restore-session.txt` | `--backup`, loss of all tables and procedures, `--restore`, verification |
| `explain-plans.txt` | EXPLAIN output for the application's main queries |

The demo session was produced with input piped from `demo-input.txt`; the option
`-Dsms.echoInput=true` makes the program print each typed value after its prompt, so the log
reads like an interactive session.

### Excerpt from `test-run.txt`

```
[measurement] 1000 inserts: row-by-row 1138 ms, JDBC batch 47 ms
.
'-- JUnit Jupiter [OK]
  +-- EnrollmentDAOTest [OK]
  ...
Test run finished after 7323 ms
[        15 containers found      ]
[       112 tests found           ]
[       112 tests successful      ]
[         0 tests failed          ]
```

## 6. Manual test checklist

For a quick manual check after installation (all of these are also in `demo-session.txt`):

1. Option 1 with `y` on an empty server -> four `[OK]` lines, 10 students / 6 courses / 16 enrollments.
2. Option 2 with an invalid e-mail and a wrong date format -> the question is repeated with the reason.
3. Option 2 with course codes `CS101, CS201` -> "committed as one transaction".
4. Option 2 with course codes `PH101, XX999` -> "Transaction rolled back - the student was NOT saved."
5. Option 2 with an existing e-mail -> "A record with the same unique value already exists ... error 1062".
6. Option 7 -> 1 twice -> first "Batch committed: 8 student(s)", second "Batch failed - transaction rolled back".
7. Option 8 with `Mathematics` -> rows returned by `GetStudentsByDepartment`.
8. Option 9 -> report with counts from `GetDatabaseStatistics`, pool status, indexes, EXPLAIN check.
9. `run --backup`, drop a table, `run --restore <file>` -> data back.
