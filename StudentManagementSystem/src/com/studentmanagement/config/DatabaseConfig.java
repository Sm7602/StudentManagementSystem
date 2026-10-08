package com.studentmanagement.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.apache.commons.dbcp2.BasicDataSource;

/**
 * Central JDBC configuration.
 *
 * <p>Loads {@code config/database.properties} and owns the single Apache Commons DBCP2
 * connection pool that every DAO borrows connections from. Closing a borrowed connection
 * returns it to the pool.</p>
 *
 * <ul>
 *   <li>Another configuration file can be selected with {@code -Ddb.config=<path>}
 *       (the tests use {@code config/database-test.properties}).</li>
 *   <li>The environment variables {@code DB_USERNAME} and {@code DB_PASSWORD} override
 *       the credentials in the file, so passwords do not have to be stored on disk.</li>
 * </ul>
 */
public final class DatabaseConfig {

    /** System property that selects the configuration file. */
    public static final String CONFIG_FILE_PROPERTY = "db.config";
    /** Configuration file used when {@value #CONFIG_FILE_PROPERTY} is not set. */
    public static final String DEFAULT_CONFIG_FILE = "config/database.properties";
    /** MySQL Connector/J driver class. */
    public static final String DRIVER_CLASS = "com.mysql.cj.jdbc.Driver";

    private static final Logger LOGGER = Logger.getLogger(DatabaseConfig.class.getName());

    /** jdbc:mysql://host[:port]/database[?parameters] */
    private static final Pattern MYSQL_URL =
            Pattern.compile("^jdbc:mysql://([^/:?]+)(?::(\\d+))?/([A-Za-z0-9_]+)(\\?.*)?$");
    private static final int DEFAULT_PORT = 3306;

    private static Properties properties;
    private static BasicDataSource dataSource;

    private DatabaseConfig() {
        // static configuration holder
    }

    // ------------------------------------------------------------------ pool

    /**
     * Returns the shared connection pool, creating it on first use.
     * No connection is opened until the first {@link DataSource#getConnection()} call.
     */
    public static synchronized DataSource getDataSource() {
        if (dataSource == null) {
            dataSource = createDataSource();
            LOGGER.info(() -> "Connection pool created for " + getUrl());
        }
        return dataSource;
    }

    /** Borrows a connection from the pool; closing it returns it to the pool. */
    public static Connection getConnection() throws SQLException {
        return getDataSource().getConnection();
    }

    /** Closes the pool and its idle connections. Called when the application exits. */
    public static synchronized void closePool() {
        if (dataSource == null) {
            return;
        }
        try {
            dataSource.close();
            LOGGER.info("Connection pool closed");
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Error while closing the connection pool", e);
        } finally {
            dataSource = null;
        }
    }

    /**
     * Snapshot of the pool configuration and its current usage
     * (used by the statistics report and the tests).
     */
    public static synchronized Map<String, Integer> getPoolStatistics() {
        BasicDataSource pool = (BasicDataSource) getDataSource();
        Map<String, Integer> stats = new LinkedHashMap<>();
        stats.put("active", pool.getNumActive());
        stats.put("idle", pool.getNumIdle());
        stats.put("initialSize", pool.getInitialSize());
        stats.put("minIdle", pool.getMinIdle());
        stats.put("maxIdle", pool.getMaxIdle());
        stats.put("maxTotal", pool.getMaxTotal());
        stats.put("maxOpenPreparedStatements", pool.getMaxOpenPreparedStatements());
        return stats;
    }

    private static BasicDataSource createDataSource() {
        int initialSize = intProperty("pool.initialSize", 5);
        int minIdle = intProperty("pool.minIdle", 5);
        int maxIdle = intProperty("pool.maxIdle", 10);
        int maxTotal = intProperty("pool.maxTotal", 20);
        if (minIdle > maxIdle || maxIdle > maxTotal || initialSize > maxTotal) {
            throw new IllegalStateException("Invalid pool settings: require minIdle <= maxIdle <= maxTotal"
                    + " and initialSize <= maxTotal (got minIdle=" + minIdle + ", maxIdle=" + maxIdle
                    + ", maxTotal=" + maxTotal + ", initialSize=" + initialSize + ")");
        }

        BasicDataSource pool = new BasicDataSource();
        pool.setDriverClassName(DRIVER_CLASS);
        pool.setUrl(getUrl());
        pool.setUsername(getUsername());
        pool.setPassword(getPassword());

        pool.setInitialSize(initialSize);
        pool.setMinIdle(minIdle);
        pool.setMaxIdle(maxIdle);
        pool.setMaxTotal(maxTotal);
        pool.setMaxWait(Duration.ofMillis(intProperty("pool.maxWaitMillis", 10_000)));

        // PreparedStatement caching per pooled connection.
        pool.setPoolPreparedStatements(true);
        pool.setMaxOpenPreparedStatements(intProperty("pool.maxOpenPreparedStatements", 100));

        // DBCP2 maintains minIdle only while the evictor thread runs.
        pool.setDurationBetweenEvictionRuns(
                Duration.ofMillis(intProperty("pool.timeBetweenEvictionRunsMillis", 30_000)));

        // Validate (Connection.isValid) before a connection is handed out.
        pool.setTestOnBorrow(true);
        // Safety net: a connection returned with an open transaction is rolled back
        // and put back into auto-commit mode.
        pool.setDefaultAutoCommit(true);
        pool.setRollbackOnReturn(true);
        pool.setAutoCommitOnReturn(true);
        return pool;
    }

    // ------------------------------------------------------- configuration

    /** Full JDBC URL of the application database. */
    public static String getUrl() {
        String url = requiredProperty("db.url");
        if (!MYSQL_URL.matcher(url).matches()) {
            throw new IllegalStateException("db.url must look like "
                    + "jdbc:mysql://localhost:3306/student_management[?parameters] but was: " + url);
        }
        return url;
    }

    /** Name of the application database, taken from {@code db.url}. */
    public static String getDatabaseName() {
        return urlPart(3);
    }

    /** MySQL server host name, taken from {@code db.url}. */
    public static String getHost() {
        return urlPart(1);
    }

    /** MySQL server port, taken from {@code db.url} (3306 when omitted). */
    public static int getPort() {
        String port = urlPart(2);
        return port == null ? DEFAULT_PORT : Integer.parseInt(port);
    }

    /**
     * URL of the MySQL server without a default database. Used once by
     * {@link DatabaseInitializer} to create the application database.
     */
    public static String getServerUrl() {
        String parameters = urlPart(4);
        return "jdbc:mysql://" + getHost() + ":" + getPort() + "/" + (parameters == null ? "" : parameters);
    }

    /** User name: {@code DB_USERNAME} environment variable or {@code db.username}. */
    public static String getUsername() {
        String fromEnvironment = System.getenv("DB_USERNAME");
        return isSet(fromEnvironment) ? fromEnvironment : requiredProperty("db.username");
    }

    /** Password: {@code DB_PASSWORD} environment variable or {@code db.password} (may be empty). */
    public static String getPassword() {
        String fromEnvironment = System.getenv("DB_PASSWORD");
        return isSet(fromEnvironment) ? fromEnvironment : getProperties().getProperty("db.password", "");
    }

    /** Directory that contains schema.sql, procedures.sql and seed.sql. */
    public static Path getScriptsDirectory() {
        return Paths.get(getProperties().getProperty("app.sqlScriptsDir", "database").trim());
    }

    /** Directory where backups are written. */
    public static Path getBackupDirectory() {
        return Paths.get(getProperties().getProperty("backup.directory", "backups").trim());
    }

    /** Command (or full path) of the mysqldump tool. */
    public static String getMysqldumpPath() {
        return getProperties().getProperty("backup.mysqldumpPath", "mysqldump").trim();
    }

    /** Command (or full path) of the mysql command-line client. */
    public static String getMysqlPath() {
        return getProperties().getProperty("backup.mysqlPath", "mysql").trim();
    }

    private static String urlPart(int group) {
        Matcher matcher = MYSQL_URL.matcher(getUrl());
        if (!matcher.matches()) {
            throw new IllegalStateException("Invalid db.url: " + getUrl());
        }
        return matcher.group(group);
    }

    private static synchronized Properties getProperties() {
        if (properties == null) {
            Path file = Paths.get(System.getProperty(CONFIG_FILE_PROPERTY, DEFAULT_CONFIG_FILE));
            if (!Files.isRegularFile(file)) {
                throw new IllegalStateException("Database configuration file not found: " + file.toAbsolutePath()
                        + " - run the application from the project root directory (see README.md)");
            }
            Properties loaded = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                loaded.load(in);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read database configuration file " + file, e);
            }
            properties = loaded;
            LOGGER.info(() -> "Loaded database configuration from " + file);
        }
        return properties;
    }

    private static String requiredProperty(String key) {
        String value = getProperties().getProperty(key);
        if (!isSet(value)) {
            throw new IllegalStateException("Missing required property '" + key + "' in the database configuration");
        }
        return value.trim();
    }

    private static int intProperty(String key, int defaultValue) {
        String value = getProperties().getProperty(key);
        if (!isSet(value)) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 0) {
                throw new NumberFormatException("negative");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Property '" + key + "' must be a non-negative integer but was: " + value, e);
        }
    }

    private static boolean isSet(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
