package com.studentmanagement.util;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.sql.DataSource;

import com.studentmanagement.config.DatabaseConfig;

/**
 * Reusable JDBC helpers shared by the DAOs, services and the initializer:
 * <ul>
 *   <li>{@link #executeInTransaction} - commit / rollback template for multi-statement work</li>
 *   <li>{@link #setParameters} - binds values to a PreparedStatement</li>
 *   <li>{@link #describe} - user-friendly text for an SQLException</li>
 *   <li>{@link #readSqlScript} / {@link #executeStatements} - runs the .sql files (DELIMITER aware)</li>
 *   <li>{@link #backupDatabase} / {@link #restoreDatabase} - logical backup and recovery
 *       with the MySQL client tools</li>
 * </ul>
 */
public final class DatabaseUtils {

    private static final Logger LOGGER = Logger.getLogger(DatabaseUtils.class.getName());

    private static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    private DatabaseUtils() {
        // static helpers only
    }

    // =====================================================================
    // Transactions
    // =====================================================================

    /** A unit of work that runs on one connection inside a transaction. */
    @FunctionalInterface
    public interface TransactionWork<T> {
        T execute(Connection connection) throws SQLException;
    }

    /**
     * Runs {@code work} as a single transaction on a pooled connection:
     * auto-commit is switched off, the work is committed if it completes, and rolled back
     * if it throws (SQLException or RuntimeException). Auto-commit is restored and the
     * connection is returned to the pool in every case.
     */
    public static <T> T executeInTransaction(DataSource dataSource, TransactionWork<T> work) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                rollback(connection, e);
                throw e;
            } finally {
                restoreAutoCommit(connection, originalAutoCommit);
            }
        }
    }

    private static void rollback(Connection connection, Exception cause) {
        try {
            connection.rollback();
            LOGGER.log(Level.INFO, "Transaction rolled back because of: {0}", cause.toString());
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
            LOGGER.log(Level.SEVERE, "Rollback failed", rollbackFailure);
        }
    }

    private static void restoreAutoCommit(Connection connection, boolean autoCommit) {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException e) {
            // The pool also resets auto-commit on return (autoCommitOnReturn=true).
            LOGGER.log(Level.WARNING, "Could not restore auto-commit mode", e);
        }
    }

    // =====================================================================
    // Statement helpers and error messages
    // =====================================================================

    /** Binds {@code values} to the placeholders of {@code statement} (1-based, in order). */
    public static void setParameters(PreparedStatement statement, List<?> values) throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (value == null) {
                statement.setNull(i + 1, Types.NULL);
            } else {
                statement.setObject(i + 1, value);
            }
        }
    }

    /**
     * Translates an SQLException into a message for the user, followed by the technical
     * details (MySQL message, SQLState and vendor error code) needed for troubleshooting.
     */
    public static String describe(SQLException exception) {
        SQLException specific = mostSpecific(exception);
        int code = specific.getErrorCode();
        String sqlState = specific.getSQLState() == null ? "n/a" : specific.getSQLState();
        String friendly;
        switch (code) {
            case 1062:
                friendly = "A record with the same unique value already exists";
                break;
            case 1451:
                friendly = "The record cannot be deleted or changed because other records depend on it";
                break;
            case 1452:
                friendly = "A referenced record (student or course) does not exist";
                break;
            case 3819:
                friendly = "A value breaks a database rule (CHECK constraint)";
                break;
            case 1406:
                friendly = "A value is too long for its column";
                break;
            case 1045:
                friendly = "Access denied - check db.username and the password (DB_PASSWORD) in the configuration";
                break;
            case 1044:
            case 1142:
            case 1370:
                friendly = "The MySQL account lacks a required privilege on the database";
                break;
            case 1049:
                friendly = "The database does not exist yet - choose option 1 (Initialize Database)";
                break;
            case 1146:
                friendly = "A table is missing - choose option 1 (Initialize Database)";
                break;
            case 1305:
                friendly = "A stored procedure is missing - choose option 1 (Initialize Database)";
                break;
            default:
                friendly = sqlState.startsWith("08")
                        ? "Cannot connect to MySQL - check that the server is running and that db.url is correct"
                        : "The database reported an error";
                break;
        }
        String details = String.valueOf(specific.getMessage()).replaceAll("\\s+", " ").trim();
        return friendly + " [MySQL: " + details + " | SQLState " + sqlState + ", error " + code + "]";
    }

    /**
     * The pool and the driver wrap errors (e.g. "Cannot create PoolableConnectionFactory"),
     * so the cause chain is searched for the first exception that carries a MySQL error code.
     */
    static SQLException mostSpecific(SQLException exception) {
        SQLException withState = null;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = exception;
        while (current != null && seen.add(current)) {
            if (current instanceof SQLException) {
                SQLException sqlException = (SQLException) current;
                if (sqlException.getErrorCode() != 0) {
                    return sqlException;
                }
                if (withState == null && sqlException.getSQLState() != null) {
                    withState = sqlException;
                }
                current = sqlException.getNextException() != null ? sqlException.getNextException() : sqlException.getCause();
            } else {
                current = current.getCause();
            }
        }
        return withState != null ? withState : exception;
    }

    // =====================================================================
    // SQL scripts
    // =====================================================================

    /** Reads a .sql file (UTF-8) and splits it into statements. */
    public static List<String> readSqlScript(Path script) throws IOException {
        if (!Files.isRegularFile(script)) {
            throw new FileNotFoundException("SQL script not found: " + script.toAbsolutePath()
                    + " - run the application from the project root directory");
        }
        return parseSqlScript(new String(Files.readAllBytes(script), StandardCharsets.UTF_8));
    }

    /**
     * Executes trusted statements read from the project's own .sql files.
     * (Static DDL/DML without user input, so a plain Statement is appropriate.)
     */
    public static int executeStatements(Connection connection, List<String> statements) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                try {
                    statement.execute(sql);
                } catch (SQLException e) {
                    throw new SQLException("Statement failed: " + abbreviate(sql) + " -> " + e.getMessage(),
                            e.getSQLState(), e.getErrorCode(), e);
                }
            }
        }
        return statements.size();
    }

    /**
     * Splits a MySQL script into executable statements, the same way the mysql client does:
     * honours {@code DELIMITER} lines (needed for stored procedures), skips
     * {@code --}, {@code #} and block comments, and ignores delimiters inside quotes.
     */
    public static List<String> parseSqlScript(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String delimiter = ";";
        boolean inBlockComment = false;
        char quote = 0;

        for (String line : script.split("\r?\n", -1)) {
            if (quote == 0 && !inBlockComment && current.toString().trim().isEmpty()) {
                String trimmed = line.trim();
                if (trimmed.regionMatches(true, 0, "DELIMITER", 0, 9)
                        && trimmed.length() > 9 && Character.isWhitespace(trimmed.charAt(9))) {
                    delimiter = trimmed.substring(10).trim();
                    if (delimiter.isEmpty()) {
                        throw new IllegalArgumentException("DELIMITER statement without a delimiter");
                    }
                    current.setLength(0);
                    continue;
                }
            }
            int i = 0;
            while (i < line.length()) {
                char c = line.charAt(i);
                if (inBlockComment) {
                    if (line.startsWith("*/", i)) {
                        inBlockComment = false;
                        i += 2;
                    } else {
                        i++;
                    }
                } else if (quote != 0) {
                    current.append(c);
                    if (c == '\\' && quote != '`' && i + 1 < line.length()) {
                        current.append(line.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    if (c == quote) {
                        if (i + 1 < line.length() && line.charAt(i + 1) == quote) {
                            current.append(quote);       // doubled quote inside a literal
                            i += 2;
                            continue;
                        }
                        quote = 0;
                    }
                    i++;
                } else if (c == '\'' || c == '"' || c == '`') {
                    quote = c;
                    current.append(c);
                    i++;
                } else if (c == '#' || (line.startsWith("--", i)
                        && (i + 2 == line.length() || Character.isWhitespace(line.charAt(i + 2))))) {
                    break;                                // comment until end of line
                } else if (line.startsWith("/*", i)) {
                    inBlockComment = true;
                    i += 2;
                } else if (line.startsWith(delimiter, i)) {
                    addStatement(statements, current);
                    i += delimiter.length();
                } else {
                    current.append(c);
                    i++;
                }
            }
            if (current.length() > 0) {
                current.append('\n');
            }
        }
        if (quote != 0) {
            throw new IllegalArgumentException("Unterminated quoted text in SQL script");
        }
        addStatement(statements, current);
        return statements;
    }

    private static void addStatement(List<String> statements, StringBuilder buffer) {
        String sql = buffer.toString().trim();
        if (!sql.isEmpty()) {
            statements.add(sql);
        }
        buffer.setLength(0);
    }

    private static String abbreviate(String sql) {
        String oneLine = sql.replaceAll("\\s+", " ");
        return oneLine.length() <= 80 ? oneLine : oneLine.substring(0, 77) + "...";
    }

    // =====================================================================
    // Backup and recovery
    // =====================================================================

    /**
     * Creates a logical backup of the application database with {@code mysqldump}:
     * table definitions, data and stored procedures, taken as one consistent InnoDB
     * snapshot ({@code --single-transaction}, no table locks).
     *
     * <p>The file name is {@code <database>_<yyyyMMdd_HHmmss_SSS>.sql}. Credentials are passed
     * through a temporary option file readable only by the current user (never on the
     * command line), which is deleted afterwards.</p>
     *
     * @return the backup file
     */
    public static Path backupDatabase(Path backupDirectory) throws IOException, InterruptedException {
        Files.createDirectories(backupDirectory);
        String database = DatabaseConfig.getDatabaseName();
        Path backupFile = backupDirectory.resolve(database + "_" + LocalDateTime.now().format(BACKUP_TIMESTAMP) + ".sql");
        Path optionFile = createClientOptionFile();
        try {
            List<String> command = Arrays.asList(
                    DatabaseConfig.getMysqldumpPath(),
                    "--defaults-extra-file=" + optionFile,   // must be the first option
                    "--single-transaction",                  // consistent snapshot without locking
                    "--routines",                            // include stored procedures
                    "--triggers",
                    "--no-tablespaces",                      // no PROCESS privilege needed
                    "--databases", database);                // adds CREATE DATABASE IF NOT EXISTS + USE
            runProcess(command, null, backupFile);
        } catch (IOException | InterruptedException | RuntimeException e) {
            Files.deleteIfExists(backupFile);                // never leave a partial backup behind
            throw e;
        } finally {
            Files.deleteIfExists(optionFile);
        }
        LOGGER.info(() -> "Backup created: " + backupFile.toAbsolutePath());
        return backupFile;
    }

    /**
     * Restores a backup created by {@link #backupDatabase} using the {@code mysql} client.
     * The file recreates the database if needed and replaces its tables, data and stored
     * procedures with the backed-up versions.
     */
    public static void restoreDatabase(Path backupFile) throws IOException, InterruptedException {
        if (!Files.isRegularFile(backupFile)) {
            throw new FileNotFoundException("Backup file not found: " + backupFile.toAbsolutePath());
        }
        Path optionFile = createClientOptionFile();
        try {
            runProcess(Arrays.asList(DatabaseConfig.getMysqlPath(), "--defaults-extra-file=" + optionFile),
                    backupFile, null);
        } finally {
            Files.deleteIfExists(optionFile);
        }
        LOGGER.info(() -> "Database restored from " + backupFile.toAbsolutePath());
    }

    /** Writes a [client] option file so that the password never appears in the process list. */
    private static Path createClientOptionFile() throws IOException {
        Path file = Files.createTempFile("sms-mysql-", ".cnf");
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Windows (no POSIX permissions): the file lives in the user's private temp folder.
            LOGGER.fine("POSIX permissions not supported for " + file);
        }
        String content = "[client]\n"
                + "host=" + DatabaseConfig.getHost() + "\n"
                + "port=" + DatabaseConfig.getPort() + "\n"
                + "protocol=TCP\n"
                + "user=" + quoteOptionValue(DatabaseConfig.getUsername()) + "\n"
                + "password=" + quoteOptionValue(DatabaseConfig.getPassword()) + "\n"
                + "default-character-set=utf8mb4\n";
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String quoteOptionValue(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void runProcess(List<String> command, Path input, Path output)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (input != null) {
            builder.redirectInput(input.toFile());
        }
        builder.redirectOutput(output != null
                ? ProcessBuilder.Redirect.to(output.toFile())
                : ProcessBuilder.Redirect.DISCARD);
        String tool = Paths.get(command.get(0)).getFileName().toString();
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IOException("Cannot run '" + command.get(0) + "'. Install the MySQL client tools or set "
                    + "backup.mysqldumpPath / backup.mysqlPath in the configuration to their full path", e);
        }
        try {
            String errors;
            try (InputStream errorStream = process.getErrorStream()) {
                errors = new String(errorStream.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException(tool + " failed with exit code " + exitCode + (errors.isEmpty() ? "" : ": " + errors));
            }
            if (!errors.isEmpty()) {
                LOGGER.warning(tool + " reported: " + errors);
            }
        } catch (InterruptedException e) {
            process.destroy();
            throw e;
        }
    }
}
