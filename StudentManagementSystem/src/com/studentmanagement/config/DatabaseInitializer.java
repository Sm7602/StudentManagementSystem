package com.studentmanagement.config;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import com.studentmanagement.util.DatabaseUtils;

/**
 * Creates the database objects from the SQL scripts in {@code database/}
 * (menu option 1 "Initialize Database").
 *
 * <ol>
 *   <li>creates the database itself if it does not exist (utf8mb4),</li>
 *   <li>runs {@code schema.sql} - tables, constraints and indexes (idempotent),</li>
 *   <li>runs {@code procedures.sql} - stored procedures (dropped and recreated),</li>
 *   <li>optionally runs {@code seed.sql} in one transaction, only if all tables are empty.</li>
 * </ol>
 * Existing data is never deleted.
 */
public class DatabaseInitializer {

    static final String SCHEMA_SCRIPT = "schema.sql";
    static final String PROCEDURES_SCRIPT = "procedures.sql";
    static final String SEED_SCRIPT = "seed.sql";

    private static final Logger LOGGER = Logger.getLogger(DatabaseInitializer.class.getName());

    private static final String COUNT_TABLES_SQL = "SELECT COUNT(*) FROM information_schema.tables "
            + "WHERE table_schema = ? AND table_name IN ('students', 'courses', 'student_courses')";
    private static final String COUNT_PROCEDURES_SQL = "SELECT COUNT(*) FROM information_schema.routines "
            + "WHERE routine_schema = ? AND routine_type = 'PROCEDURE' "
            + "AND routine_name IN ('GetStudentsByDepartment', 'GetDatabaseStatistics')";
    private static final String COUNT_ROWS_SQL = "SELECT (SELECT COUNT(*) FROM students), "
            + "(SELECT COUNT(*) FROM courses), (SELECT COUNT(*) FROM student_courses)";

    private final Path scriptsDirectory;

    /** Uses the script directory from the configuration ({@code app.sqlScriptsDir}). */
    public DatabaseInitializer() {
        this(DatabaseConfig.getScriptsDirectory());
    }

    public DatabaseInitializer(Path scriptsDirectory) {
        this.scriptsDirectory = scriptsDirectory;
    }

    /**
     * Creates or verifies all database objects.
     *
     * @param loadSampleData load {@code seed.sql} if the database contains no data yet
     * @return a description of every step performed, in order
     */
    public List<String> initializeDatabase(boolean loadSampleData) throws SQLException, IOException {
        List<String> steps = new ArrayList<>();
        createDatabaseIfNotExists();
        steps.add("Database '" + DatabaseConfig.getDatabaseName() + "' is ready (utf8mb4)");

        List<String> schema = DatabaseUtils.readSqlScript(scriptsDirectory.resolve(SCHEMA_SCRIPT));
        List<String> procedures = DatabaseUtils.readSqlScript(scriptsDirectory.resolve(PROCEDURES_SCRIPT));
        try (Connection connection = DatabaseConfig.getConnection()) {
            DatabaseUtils.executeStatements(connection, schema);
            steps.add("Tables, constraints and indexes created/verified (" + SCHEMA_SCRIPT + ", "
                    + schema.size() + " statements)");
            DatabaseUtils.executeStatements(connection, procedures);
            steps.add("Stored procedures GetStudentsByDepartment and GetDatabaseStatistics created ("
                    + PROCEDURES_SCRIPT + ", " + procedures.size() + " statements)");
        }

        if (loadSampleData) {
            steps.add(loadSampleDataIfEmpty());
        }
        LOGGER.info(() -> "Database initialized: " + steps);
        return steps;
    }

    /**
     * Creates the configured database if it is missing. A direct (non-pooled) server
     * connection is needed because the pool's URL points to the database itself.
     */
    public void createDatabaseIfNotExists() throws SQLException {
        // The name comes from db.url and is restricted to [A-Za-z0-9_] by DatabaseConfig;
        // identifiers cannot be bound as PreparedStatement parameters.
        String sql = "CREATE DATABASE IF NOT EXISTS `" + DatabaseConfig.getDatabaseName()
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci";
        try (Connection connection = DriverManager.getConnection(DatabaseConfig.getServerUrl(),
                DatabaseConfig.getUsername(), DatabaseConfig.getPassword());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    /** True when all three tables and both stored procedures exist. */
    public boolean isSchemaInitialized() throws SQLException {
        String database = DatabaseConfig.getDatabaseName();
        try (Connection connection = DatabaseConfig.getConnection()) {
            return count(connection, COUNT_TABLES_SQL, database) == 3
                    && count(connection, COUNT_PROCEDURES_SQL, database) == 2;
        }
    }

    private String loadSampleDataIfEmpty() throws SQLException, IOException {
        List<String> seed = DatabaseUtils.readSqlScript(scriptsDirectory.resolve(SEED_SCRIPT));
        return DatabaseUtils.executeInTransaction(DatabaseConfig.getDataSource(), connection -> {
            int[] before = rowCounts(connection);
            if (before[0] + before[1] + before[2] > 0) {
                return "Sample data skipped: the database already contains data";
            }
            DatabaseUtils.executeStatements(connection, seed);
            int[] after = rowCounts(connection);
            return "Sample data loaded in one transaction (" + after[0] + " students, " + after[1]
                    + " courses, " + after[2] + " enrollments)";
        });
    }

    private static int[] rowCounts(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(COUNT_ROWS_SQL)) {
            rs.next();
            return new int[] {rs.getInt(1), rs.getInt(2), rs.getInt(3)};
        }
    }

    private static int count(Connection connection, String sql, String schema) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
