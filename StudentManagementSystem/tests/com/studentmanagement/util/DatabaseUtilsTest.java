package com.studentmanagement.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.studentmanagement.TestDatabase;

/** SQL script parsing, the transaction template, parameter binding and error messages. */
class DatabaseUtilsTest {

    private DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = TestDatabase.dataSource();
        TestDatabase.resetTables();
    }

    // ------------------------------------------------------------ script parser

    @Test
    void scriptIsSplitOnSemicolonsAndCommentsAreSkipped() {
        List<String> statements = DatabaseUtils.parseSqlScript(String.join("\n",
                "-- a line comment",
                "# another comment",
                "CREATE TABLE a (id INT);  -- trailing comment",
                "/* block",
                "   comment */ INSERT INTO a VALUES (1);",
                "SELECT 1 -- no delimiter at the end"));

        assertEquals(Arrays.asList("CREATE TABLE a (id INT)", "INSERT INTO a VALUES (1)", "SELECT 1"), statements);
    }

    @Test
    void delimiterLinesKeepStoredProcedureBodiesTogether() {
        List<String> statements = DatabaseUtils.parseSqlScript(String.join("\n",
                "DROP PROCEDURE IF EXISTS p;",
                "DELIMITER //",
                "CREATE PROCEDURE p()",
                "BEGIN",
                "  SELECT 1;",
                "  SELECT 2;",
                "END //",
                "DELIMITER ;",
                "SELECT 3;"));

        assertEquals(3, statements.size());
        assertEquals("CREATE PROCEDURE p()\nBEGIN\n  SELECT 1;\n  SELECT 2;\nEND", statements.get(1));
        assertEquals("SELECT 3", statements.get(2));
    }

    @Test
    void delimitersInsideQuotesAreNotStatementEnds() {
        List<String> statements = DatabaseUtils.parseSqlScript(
                "INSERT INTO t VALUES ('a;b', \"c;d\", 'it''s; fine', 'back\\'slash;');SELECT `odd;name` FROM t;");

        assertEquals(Arrays.asList("INSERT INTO t VALUES ('a;b', \"c;d\", 'it''s; fine', 'back\\'slash;')",
                "SELECT `odd;name` FROM t"), statements);
    }

    @Test
    void unterminatedQuotesAreReported() {
        assertThrows(IllegalArgumentException.class, () -> DatabaseUtils.parseSqlScript("SELECT 'oops;"));
    }

    @Test
    void theProjectScriptsParseIntoTheExpectedStatements() throws Exception {
        List<String> schema = DatabaseUtils.readSqlScript(Paths.get("database/schema.sql"));
        List<String> procedures = DatabaseUtils.readSqlScript(Paths.get("database/procedures.sql"));
        List<String> seed = DatabaseUtils.readSqlScript(Paths.get("database/seed.sql"));

        assertEquals(3, schema.size());
        assertTrue(schema.stream().allMatch(sql -> sql.startsWith("CREATE TABLE IF NOT EXISTS")));
        assertEquals(4, procedures.size());
        assertTrue(procedures.get(2).startsWith("CREATE PROCEDURE GetStudentsByDepartment"));
        assertTrue(procedures.get(3).startsWith("CREATE PROCEDURE GetDatabaseStatistics"));
        assertEquals(3, seed.size());
        assertTrue(seed.stream().allMatch(sql -> sql.startsWith("INSERT INTO")));
    }

    // ------------------------------------------------------------ transactions

    @Test
    void transactionIsCommittedWhenTheWorkSucceeds() throws SQLException {
        int inserted = DatabaseUtils.executeInTransaction(dataSource, connection -> {
            insertCourse(connection, "CS101");
            insertCourse(connection, "CS201");
            return 2;
        });

        assertEquals(2, inserted);
        assertEquals(2, TestDatabase.countRows("courses"));
    }

    @Test
    void transactionIsRolledBackWhenAStatementFails() {
        SQLException error = assertThrows(SQLException.class, () ->
                DatabaseUtils.executeInTransaction(dataSource, connection -> {
                    insertCourse(connection, "CS101");      // succeeds ...
                    insertCourse(connection, "CS101");      // ... then a duplicate key fails
                    return null;
                }));

        assertEquals(1062, error.getErrorCode());
        assertEquals(0, countCourses(), "the first INSERT was rolled back");
    }

    @Test
    void transactionIsRolledBackOnARuntimeException() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                DatabaseUtils.executeInTransaction(dataSource, connection -> {
                    insertCourse(connection, "CS101");
                    throw new IllegalStateException("business rule violated");
                }));

        assertEquals("business rule violated", error.getMessage());
        assertEquals(0, countCourses());
    }

    @Test
    void autoCommitIsRestoredBeforeTheConnectionGoesBackToThePool() throws SQLException {
        Connection[] used = new Connection[1];
        DatabaseUtils.executeInTransaction(dataSource, connection -> {
            used[0] = connection;
            assertFalse(connection.getAutoCommit(), "auto-commit is off inside the transaction");
            return null;
        });

        assertTrue(used[0].isClosed(), "the connection was closed (returned to the pool)");
        try (Connection next = dataSource.getConnection()) {
            assertTrue(next.getAutoCommit());
        }
        assertEquals(0, TestDatabase.activeConnections());
    }

    // ------------------------------------------------------ parameter binding

    @Test
    void setParametersBindsValuesAndNullsInOrder() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT ?, ? IS NULL, ?")) {
            DatabaseUtils.setParameters(statement, Arrays.asList("text", null, 42));
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                assertEquals("text", rs.getString(1));
                assertTrue(rs.getBoolean(2));
                assertEquals(42, rs.getInt(3));
            }
        }
    }

    // ------------------------------------------------------- error messages

    @Test
    void mySqlErrorCodesAreTranslatedIntoFriendlyMessages() {
        assertTrue(DatabaseUtils.describe(new SQLException("Duplicate entry 'x' for key 'students.uk_students_email'",
                "23000", 1062)).startsWith("A record with the same unique value already exists [MySQL: Duplicate entry"));
        assertTrue(DatabaseUtils.describe(new SQLException("fk", "23000", 1452)).startsWith("A referenced record"));
        assertTrue(DatabaseUtils.describe(new SQLException("fk", "23000", 1451)).startsWith("The record cannot be deleted"));
        assertTrue(DatabaseUtils.describe(new SQLException("chk", "HY000", 3819)).contains("CHECK constraint"));
        assertTrue(DatabaseUtils.describe(new SQLException("no table", "42S02", 1146)).contains("Initialize Database"));
        assertTrue(DatabaseUtils.describe(new SQLException("denied", "28000", 1045)).startsWith("Access denied"));
        assertTrue(DatabaseUtils.describe(new SQLException("unknown", "HY000", 9999)).endsWith("SQLState HY000, error 9999]"));
    }

    @Test
    void wrappedConnectionFailuresAreUnwrapped() {
        SQLException driverError = new SQLException("Communications link failure", "08S01", 0);
        SQLException poolError = new SQLException("Cannot create PoolableConnectionFactory", null, 0, driverError);

        assertSame(driverError, DatabaseUtils.mostSpecific(poolError));
        assertTrue(DatabaseUtils.describe(poolError).startsWith("Cannot connect to MySQL"));
    }

    // ------------------------------------------------------------------ helpers

    private static void insertCourse(Connection connection, String code) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO courses (course_code, course_name, credits, department) VALUES (?, 'Course', 3, 'Test')")) {
            statement.setString(1, code);
            statement.executeUpdate();
        }
    }

    private int countCourses() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM courses")) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
