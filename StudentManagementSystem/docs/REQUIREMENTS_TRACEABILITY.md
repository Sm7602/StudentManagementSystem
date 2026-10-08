# Requirements Traceability Matrix

**Source of requirements:** the task brief for "Student Management System with MySQL using Java
JDBC". (The original specification file was not available while the project was built, so every
explicit requirement of the brief is treated as the specification.) Each requirement is mapped
to the code, database object / script, automated test and captured evidence that satisfy it.

Evidence files are in [`docs/evidence/`](evidence/). Test names refer to classes under `tests/`.
All 112 tests pass ([`evidence/test-run.txt`](evidence/test-run.txt)).

## 1. Core requirements

| # | Requirement | Implementation (file -> class / method) | Database object / script | Verified by test | Evidence | Status |
|---|---|---|---|---|---|---|
| R01 | MySQL database `student_management` | `config/DatabaseInitializer.createDatabaseIfNotExists()` | `CREATE DATABASE ... utf8mb4` (also `database/install.sql`) | `DatabaseInitializerTest` | demo-session.txt (starts with no database, option 1 creates it) | ✅ |
| R02 | Tables `students`, `courses`, `student_courses` (enrollment relationship) | `DatabaseInitializer.initializeDatabase()` | `database/schema.sql` | `DatabaseInitializerTest.createsAllTables...` | demo-session.txt option 1 | ✅ |
| R03 | Primary keys, foreign keys, unique, NOT NULL, data types, relationships, timestamps | - | `schema.sql`: PKs, `fk_student_courses_student` (CASCADE), `fk_student_courses_course` (RESTRICT), `uk_students_email`, `uk_courses_code`, 4 CHECKs, `created_at`/`updated_at` | `DatabaseInitializerTest`; constraint tests in `StudentDAOTest`, `CourseDAOTest`, `EnrollmentDAOTest` (errors 1062, 1451, 1452, 3819) | [DATABASE_DESIGN.md](DATABASE_DESIGN.md) | ✅ |
| R04 | Indexes where required | - | `idx_students_department_gpa`, `idx_student_courses_course` (+ PK/unique indexes) | `ReportServiceTest.departmentLookupUsesTheCompositeIndex`, `...indexDefinitionsAreReadFromInformationSchema` | explain-plans.txt | ✅ |
| R05 | JDBC connection pooling with Apache Commons DBCP2 (URL, username, password, min idle, max idle, max connections, max open prepared statements) | `config/DatabaseConfig.createDataSource()` | `config/database.properties` (`pool.*`) | `DatabaseConfigTest` (5 tests) | report "Connection pool" section in demo-session.txt | ✅ |
| R06 | Connections always returned to the pool; no leaked Connection / Statement / ResultSet | try-with-resources in every DAO/service method; `DatabaseUtils.executeInTransaction` | - | `@AfterEach everyConnectionWasReturnedToThePool` in the three DAO tests and `StudentServiceTest`; `DatabaseConfigTest.closingAConnectionReturnsItToThePool`; `MainMenuTest` (0 active after a full session) | test-run.txt | ✅ |
| R07 | Model POJOs Student, Course, Enrollment (private fields, constructors, getters, setters, toString, suitable Java types) | `model/Student`, `model/Course`, `model/Enrollment` | type mapping in DATABASE_DESIGN.md §4 | used by every DAO test | - | ✅ |
| R08 | DAO for every entity | `dao/StudentDAO`, `dao/CourseDAO`, `dao/EnrollmentDAO` | the three tables | `StudentDAOTest`, `CourseDAOTest`, `EnrollmentDAOTest` | [JDBC_ARCHITECTURE.md](JDBC_ARCHITECTURE.md#3-dao-design) | ✅ |
| R09 | StudentDAO: add, get by ID, get all, search, update, delete | `addStudent`, `getStudentById`, `getAllStudents`, `searchStudents`, `updateStudent`, `deleteStudent` | `students` | `StudentDAOTest` (create/read/search/update/delete tests) | demo-session.txt options 2-6 | ✅ |
| R10 | CRUD with PreparedStatement; no user input concatenated into SQL | all DAOs; `util/QueryBuilder` for the dynamic search | - | `StudentDAOTest.searchTreatsWildcardsAndSqlInjectionAttemptsAsPlainText`, `QueryBuilderTest` (14) | JDBC_ARCHITECTURE.md §4 | ✅ |
| R11 | Transaction management: `setAutoCommit(false)`, commit, rollback on any failure, restore/close | `util/DatabaseUtils.executeInTransaction()`; used by `StudentService.registerStudentWithCourses`, `StudentService.enrollStudentsInCourse`, `StudentDAO.addStudentsBatch`, `DatabaseInitializer` (seed) | - | `StudentServiceTest` commit + 4 rollback scenarios; `DatabaseUtilsTest` (4 transaction tests); `EnrollmentDAOTest.aFailingBatchRollsBack...` | demo-session.txt: "committed as one transaction", "Transaction rolled back - the student was NOT saved." | ✅ |
| R12 | Batch operations with `addBatch()` / `executeBatch()`, auto-commit off, commit / rollback | `StudentDAO.addStudentsBatch()`, `EnrollmentDAO.addEnrollmentsBatch()`; menu 7 | `rewriteBatchedStatements=true` in `db.url` | `StudentDAOTest` (3 batch tests incl. rollback after the first chunk), `EnrollmentDAOTest` (3), `StudentServiceTest` (CSV + enrollment batches), `BatchPerformanceTest` | demo-session.txt option 7 (commit and rollback); test-run.txt measurement | ✅ |
| R13 | Stored procedure `GetStudentsByDepartment`, called from Java with CallableStatement | `StudentDAO.getStudentsByDepartment()` -> `{CALL GetStudentsByDepartment(?)}`; menu 8 | `database/procedures.sql` | `StudentDAOTest.storedProcedureReturnsTheStudentsOfOneDepartmentOrderedByName` | demo-session.txt option 8 | ✅ |
| R14 | Stored procedures (plural) - every procedure is used | `ReportService.getDatabaseTotals()` -> `{CALL GetDatabaseStatistics(?, ?, ?, ?)}` with OUT parameters; menu 9 and `--restore` | `procedures.sql` | `ReportServiceTest.totalsAreReadFromTheStoredProcedureOutParameters`, `...averageGpaIsNullForAnEmptyDatabase` | demo-session.txt option 9; backup-restore-session.txt | ✅ |
| R15 | Database backup and recovery - usable implementation and documentation (how, where, recovery, prerequisites, commands) | `DatabaseUtils.backupDatabase()` / `restoreDatabase()`; `Main --backup` / `--restore <file>` | mysqldump / mysql, files in `backups/` | `BackupRecoveryTest` (3) | backup-restore-session.txt; [BACKUP_AND_RECOVERY.md](BACKUP_AND_RECOVERY.md) | ✅ |
| R16 | Reusable database utility classes, actually used | `util/DatabaseUtils` (transactions, parameters, error text, scripts, backup), `util/QueryBuilder` | - | `DatabaseUtilsTest` (12), `QueryBuilderTest` (14) | every public utility method is called by application code | ✅ |
| R17 | Performance optimization where required (pooling, PreparedStatement, indexes, batches, transactions, procedures) - no invented numbers | `DatabaseConfig`, DAOs, `schema.sql`, batch methods | - | `BatchPerformanceTest`, `ReportServiceTest` (EXPLAIN), `DatabaseConfigTest` (session reuse) | [PERFORMANCE.md](PERFORMANCE.md) - only measured values | ✅ |
| R18 | Complete, executable SQL scripts | - | `schema.sql`, `procedures.sql`, `seed.sql`, `install.sql`, `create_app_user.sql` | `DatabaseUtilsTest.theProjectScriptsParseIntoTheExpectedStatements`, `DatabaseInitializerTest` | demo-session.txt option 1 | ✅ |
| R19 | Tests / database integration tests | `tests/` (12 classes, JUnit 5), `run-tests.sh` / `.bat`, separate DB `student_management_test` | `config/database-test.properties` | 112 tests | test-run.txt, junit-report.xml, [TESTING.md](TESTING.md) | ✅ |
| R20 | Configuration | `config/database.properties`, `config/database-test.properties`; `DB_USERNAME` / `DB_PASSWORD` override | - | `DatabaseConfigTest` | README §9 | ✅ |

## 2. Application (console) requirements

| # | Requirement | Implementation | Verified by | Evidence | Status |
|---|---|---|---|---|---|
| A01 | Menu: 1 Initialize Database ... 10 Exit, every option works | `Main.run()` and one handler per option | `MainMenuTest.everyMenuOptionWorksEndToEnd` | demo-session.txt | ✅ |
| A02 | 1. Initialize Database | `Main.initializeDatabase()` -> `DatabaseInitializer` | `DatabaseInitializerTest`, `MainMenuTest` | demo-session.txt | ✅ |
| A03 | 2. Add New Student | `Main.addNewStudent()` -> `StudentService.addStudent` / `registerStudentWithCourses` | `StudentServiceTest`, `MainMenuTest` | demo-session.txt | ✅ |
| A04 | 3. View All Students | `Main.viewAllStudents()` | `MainMenuTest` | demo-session.txt | ✅ |
| A05 | 4. Search Students (by ID; by name/e-mail, department, minimum GPA) | `Main.searchStudents()` -> `StudentDAO.searchStudents` (QueryBuilder) | `StudentDAOTest`, `MainMenuTest` | demo-session.txt | ✅ |
| A06 | 5. Update Student (Enter keeps a value) | `Main.updateStudent()` | `MainMenuTest.updateKeepsValuesWhenEnterIsPressed`, `StudentServiceTest` | demo-session.txt | ✅ |
| A07 | 6. Delete Student (with confirmation) | `Main.deleteStudent()` | `MainMenuTest`, `EnrollmentDAOTest` (cascade) | demo-session.txt | ✅ |
| A08 | 7. Batch Operations | `Main.batchOperations()`: CSV import, batch enrollment | `StudentServiceTest`, `MainMenuTest` | demo-session.txt | ✅ |
| A09 | 8. Call Stored Procedure | `Main.callStoredProcedure()` | `StudentDAOTest`, `MainMenuTest` | demo-session.txt | ✅ |
| A10 | 9. Database Statistics | `Main.showDatabaseStatistics()` -> `ReportService.generateStatisticsReport()` | `ReportServiceTest` | demo-session.txt | ✅ |
| A11 | 10. Exit (pool closed) | `Main.run()` / `DatabaseConfig.closePool()` in `main` | `MainMenuTest` | demo-session.txt "Goodbye!" | ✅ |
| A12 | Input validation: required fields, e-mail format, date format, numeric input, menu choice | `Main.readValidated/readDate/readGpa/readInt/readIdList`; rules in `StudentService.check*` | `StudentServiceTest.invalidInputIsRejectedBeforeAnySqlIsExecuted` (12 cases), `MainMenuTest.invalidInputIsRejectedWithHelpfulMessagesAndNothingIsSaved` | demo-session.txt (invalid e-mail, date, menu choice) | ✅ |
| A13 | Error handling: meaningful messages, technical details kept, no swallowed exceptions, no `printStackTrace()` | `DatabaseUtils.describe()`, handlers in `Main.run()`, `java.util.logging` to `logs/application.log` | `DatabaseUtilsTest` (error messages), `MainMenuTest.databaseErrorsAreExplainedAndTheMenuKeepsRunning` | demo-session.txt (error 1062 message) | ✅ |

## 3. Technology and structure constraints

| # | Requirement | How it is met | Status |
|---|---|---|---|
| T01 | Java, MySQL, JDBC, Apache Commons DBCP2, SQL only | `lib/` contains only the MySQL driver, DBCP2 (+ its Pool2 and Logging dependencies) and, for tests, JUnit | ✅ |
| T02 | No Spring / Spring Boot / Hibernate / JPA / Lombok / REST / frontend | none present (see `lib/README.md`) | ✅ |
| T03 | Project structure `src/com/studentmanagement/{config,model,dao,service,util}` + `Main.java`, and `database/`, `docs/`, `tests/`, `config/`, `lib/`, `README.md` | exactly these 13 source classes; no extra classes | ✅ |
| T04 | Secure coding: PreparedStatement for user values, no SQL injection, no hard-coded production secrets, clean resource handling | R10, R06; passwords via `DB_PASSWORD`; credentials for mysqldump in a temporary owner-only option file; optional least-privilege account `database/create_app_user.sql` | ✅ |

## 4. Documentation requirements

| # | Requirement | Where |
|---|---|---|
| D01 | README with the 20 required sections | [README.md](../README.md) |
| D02 | Database architecture, schema explanation, table relationships | [DATABASE_DESIGN.md](DATABASE_DESIGN.md) §1, §3, §4 |
| D03 | ER diagram | [DATABASE_DESIGN.md](DATABASE_DESIGN.md) §2, [images/er-diagram.png](images/er-diagram.png) |
| D04 | JDBC architecture, DAO architecture, transaction flow | [JDBC_ARCHITECTURE.md](JDBC_ARCHITECTURE.md), [images/architecture.png](images/architecture.png), [images/transaction-flow.png](images/transaction-flow.png) |
| D05 | Stored procedure explanation | [DATABASE_DESIGN.md](DATABASE_DESIGN.md) §6 |
| D06 | Backup / recovery procedure | [BACKUP_AND_RECOVERY.md](BACKUP_AND_RECOVERY.md) |
| D07 | Performance considerations | [PERFORMANCE.md](PERFORMANCE.md) |
| D08 | Deployment / setup guide | [DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md) |
| D09 | Testing evidence | [TESTING.md](TESTING.md), [evidence/](evidence/) |

## 5. Design decisions worth noting

* **Backup/restore as command-line options.** The specification's menu has exactly ten options,
  so backup and recovery are started with `run --backup` and `run --restore <file>` instead of
  adding menu entries. The implementation is real (mysqldump/mysql), tested and documented.
* **Second stored procedure.** `GetDatabaseStatistics` was added because the brief asks for stored
  procedures in the plural and for no unused procedures; it is used by menu option 9 and by the
  restore verification, and it shows OUT-parameter handling in addition to the result set of
  `GetStudentsByDepartment`.
* **Course and enrollment CRUD** are required at DAO level ("DAO for all entities") and fully
  tested; the specified menu is student-centred, so it uses them for course lookups, enrollment
  during registration, batch enrollment and the student detail view.
