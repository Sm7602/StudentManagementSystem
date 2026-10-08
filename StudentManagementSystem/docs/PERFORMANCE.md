# Performance Considerations

This document lists the performance measures the project implements and the evidence for each.
**Every number below was measured** in the runs saved in [`evidence/`](evidence/) (Ubuntu 24.04,
OpenJDK 21, MySQL 8.0.46 on the same machine). Absolute times depend on the hardware and will
differ on other machines; the relative effects are what matter.

| Area | Implemented in | Evidence |
|---|---|---|
| Connection pooling | `DatabaseConfig` (DBCP2) | `DatabaseConfigTest`, pool section of the statistics report |
| PreparedStatement + statement caching | all DAOs, `poolPreparedStatements=true` | code, `QueryBuilderTest` |
| Indexes | `database/schema.sql` | [`evidence/explain-plans.txt`](evidence/explain-plans.txt), `ReportServiceTest` |
| Batch processing | `StudentDAO.addStudentsBatch`, `EnrollmentDAO.addEnrollmentsBatch` | `BatchPerformanceTest` (measured) |
| Transactions | `DatabaseUtils.executeInTransaction` | service / DAO tests |
| Stored procedures | `database/procedures.sql` | `StudentDAOTest`, `ReportServiceTest` |

---

## 1. Connection pooling

Opening a MySQL connection costs a TCP handshake, TLS negotiation and authentication. The DBCP2
pool opens `initialSize` (5) connections once and reuses them:

* `DatabaseConfigTest.thePhysicalMySqlSessionIsReusedAfterClose` - two consecutive
  "borrow, query `SELECT CONNECTION_ID()`, close" cycles get the **same** MySQL session id, i.e. no
  new physical connection was opened.
* `maxTotal` (20) caps concurrent connections so a burst cannot exhaust the server's
  `max_connections`; `maxWaitMillis` (10 s) makes a caller fail with an error instead of waiting forever.
* `minIdle` (5) is maintained by the evictor thread (`timeBetweenEvictionRunsMillis`), so
  connections are ready before they are needed.
* `testOnBorrow` validates a connection with the driver's lightweight ping before use, so a
  connection dropped by the server is replaced transparently.
* The statistics report (menu 9) shows the live pool state, e.g. from the demo run:

  ```
  Connection pool (Apache Commons DBCP2)
    Active connections     0
    Idle connections       5
    Min idle / Max idle    5 / 10
    Max total              20
    Max cached statements  100
  ```

## 2. PreparedStatement and statement caching

* All SQL with values uses `PreparedStatement` (security and performance): the SQL text is
  constant, so it can be reused for any values.
* `poolPreparedStatements=true` / `maxOpenPreparedStatements=100`: DBCP2 keeps prepared statements
  per pooled connection and hands back the cached object when the same SQL is prepared again,
  instead of creating and parsing a new one.
* Connector/J uses client-side prepared statements by default. Server-side prepared statements
  (`useServerPrepStmts=true&cachePrepStmts=true` in `db.url`) are an option for workloads
  dominated by the same complex statements; the project keeps the driver default.

## 3. Indexes

Indexes were chosen from the actual queries (details and reasons in
[DATABASE_DESIGN.md](DATABASE_DESIGN.md#5-indexes)). Results of `EXPLAIN` on the demo data
(after `ANALYZE TABLE`), from [`evidence/explain-plans.txt`](evidence/explain-plans.txt):

| Query (application method) | type | key | Extra |
|---|---|---|---|
| `WHERE department = ?` (GetStudentsByDepartment) | `ref` | `idx_students_department_gpa` | Using filesort (ORDER BY name) |
| `WHERE department = ? AND gpa >= ?` (search) | `range` | `idx_students_department_gpa` (both columns, key_len 205) | Using index condition |
| `GROUP BY department` with `AVG(gpa)` (report) | `index` | `idx_students_department_gpa` | **Using index** - covering, rows not read |
| `WHERE email = ?` (the unique index that guards duplicate e-mails) | `const` | `uk_students_email` | Using index |
| `WHERE id = ?` (getStudentById) | `const` | `PRIMARY` | |
| enrollments of a student | `ref` | `PRIMARY` (leftmost column) | |
| enrollments of a course | `ref` | `idx_student_courses_course` | |
| course by code (inside transactions) | `const` | `uk_courses_code` | Using index |
| keyword search `LIKE '%text%'` | `ALL` | - | full scan (see Limitations) |

`ReportServiceTest.departmentLookupUsesTheCompositeIndex` asserts the plan (`ref` on
`idx_students_department_gpa`) automatically, and menu option 9 prints the optimizer's choice at
run time:

```
Index check (EXPLAIN for department = 'Business'): access type ref, key idx_students_department_gpa
```

## 4. Batch processing

`BatchPerformanceTest` inserts the same **1,000 students** twice into an empty table and measures
both runs with `System.nanoTime()`:

1. row by row - `StudentDAO.addStudent()` 1,000 times, each auto-committed;
2. `StudentDAO.addStudentsBatch()` - JDBC batch (`addBatch` / `executeBatch`, chunks of 500) in one
   transaction, with `rewriteBatchedStatements=true`.

Result printed by the test in [`evidence/test-run.txt`](evidence/test-run.txt):

```
[measurement] 1000 inserts: row-by-row 1138 ms, JDBC batch 47 ms
```

In this run the batch was about **24 times faster**. The reasons:

* one commit (one redo-log flush) instead of 1,000;
* with `rewriteBatchedStatements=true` the driver sends multi-row `INSERT ... VALUES (...), (...)`
  statements, so a few round trips replace 1,000;
* one borrowed connection instead of 1,000 borrow/return cycles.

The test also asserts that the batch is faster and that both runs stored exactly 1,000 rows.
In the menu, the CSV import of 8 students took 6 ms (demo session). Run `run-tests` to reproduce
the measurement on your own machine.

## 5. Transactions

Grouping related statements in one transaction is mainly about correctness (all-or-nothing),
but it also means one commit instead of one per statement - visible in the batch measurement
above. Transactions are kept short: no user input is read while a transaction is open (the
service receives complete, validated data first), so locks are held only for milliseconds.

## 6. Stored procedures

* `GetDatabaseStatistics` returns four values (three counts and the average GPA) through OUT
  parameters in **one** call, instead of four separate queries and round trips.
* `GetStudentsByDepartment` keeps the query on the server; it is called with a bound parameter
  and uses the department index.

## 7. Measured application timings (demo data, 18 students)

| Operation | Time | Source |
|---|---|---|
| Statistics report (option 9: procedure call, 3 aggregate queries, information_schema, EXPLAIN) | 30 ms | demo-session.txt |
| CSV batch import, 8 students | 6 ms | demo-session.txt |
| Full automated test suite, 112 tests incl. the 1,000-row benchmark | about 7 s | test-run.txt |

## Limitations

* **Keyword search** uses `LIKE '%text%'` so that any part of a name or e-mail matches. A leading
  wildcard cannot use a B-tree index, so this query scans the table (`type ALL`). For a student
  register this is fast; for very large tables a FULLTEXT index (`MATCH ... AGAINST`) or prefix
  search (`LIKE 'text%'`) would be used instead.
* **ORDER BY last_name, first_name** is sorted in memory (`Using filesort`) because the
  department index does not contain the names; the sorted sets (one department / one search
  result) are small.
* No result caching is implemented: every screen shows current data straight from MySQL.
* Pool sizes are defaults for a single console user; for a multi-user server set `pool.maxTotal`
  according to the expected concurrency and MySQL's `max_connections`.
