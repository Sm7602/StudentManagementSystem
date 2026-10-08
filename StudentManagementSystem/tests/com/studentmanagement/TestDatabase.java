package com.studentmanagement;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import javax.sql.DataSource;

import com.studentmanagement.config.DatabaseConfig;
import com.studentmanagement.config.DatabaseInitializer;
import com.studentmanagement.model.Course;
import com.studentmanagement.model.Student;

/**
 * Shared set-up for the database integration tests.
 *
 * <p>Tests run against the database configured in {@code config/database-test.properties}
 * ({@code student_management_test}). As a safety net, nothing runs unless the configured
 * database name ends with {@code _test}, so the real application data can never be touched.</p>
 */
public final class TestDatabase {

    /** Application log written during the tests (instead of the console). */
    private static final String TEST_LOG_FILE = "out/test-reports/application-test.log";

    static {
        // Several tests provoke errors on purpose; the application logs them with stack traces.
        // Those records go to a file so the console only shows the test results.
        Logger root = Logger.getLogger("");
        for (Handler handler : root.getHandlers()) {
            root.removeHandler(handler);
        }
        try {
            Files.createDirectories(Paths.get(TEST_LOG_FILE).getParent());
            FileHandler fileHandler = new FileHandler(TEST_LOG_FILE, true);
            fileHandler.setFormatter(new SimpleFormatter());
            root.addHandler(fileHandler);
            root.setLevel(Level.INFO);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static boolean schemaCreated;

    private TestDatabase() {
    }

    /** Returns the pool for the test database, creating the schema on first use. */
    public static synchronized DataSource dataSource() throws SQLException, IOException {
        String database = DatabaseConfig.getDatabaseName();
        if (!database.endsWith("_test")) {
            throw new IllegalStateException("Refusing to run destructive tests against database '" + database
                    + "'. Run the tests with -Ddb.config=config/database-test.properties (run-tests script).");
        }
        if (!schemaCreated) {
            new DatabaseInitializer().initializeDatabase(false);
            schemaCreated = true;
        }
        return DatabaseConfig.getDataSource();
    }

    /**
     * Empties all tables and resets AUTO_INCREMENT, so every test starts from ids 1, 2, 3 ...
     * (foreign-key checks are disabled only for this session and re-enabled afterwards).
     */
    public static void resetTables() throws SQLException {
        try (Connection connection = DatabaseConfig.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            try {
                statement.execute("TRUNCATE TABLE student_courses");
                statement.execute("TRUNCATE TABLE students");
                statement.execute("TRUNCATE TABLE courses");
            } finally {
                statement.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        }
    }

    /** Number of rows in one of the application tables. */
    public static int countRows(String table) throws SQLException {
        if (!table.matches("students|courses|student_courses")) {
            throw new IllegalArgumentException("Unknown table " + table);
        }
        try (Connection connection = DatabaseConfig.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** Number of connections currently borrowed from the pool. */
    public static int activeConnections() {
        return DatabaseConfig.getPoolStatistics().get("active");
    }

    /** A valid, not yet stored student (born 2003-05-20, enrolled 2021-08-01). */
    public static Student newStudent(String firstName, String lastName, String email, String department, String gpa) {
        return new Student(firstName, lastName, email, LocalDate.of(2003, 5, 20), department,
                gpa == null ? null : new BigDecimal(gpa), LocalDate.of(2021, 8, 1));
    }

    /** A valid, not yet stored course. */
    public static Course newCourse(String code, String name, int credits, String department) {
        return new Course(code, name, credits, department);
    }
}
