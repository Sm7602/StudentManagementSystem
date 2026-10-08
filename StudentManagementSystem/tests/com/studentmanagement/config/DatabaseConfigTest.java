package com.studentmanagement.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.studentmanagement.TestDatabase;

/** Connection pool configuration and behaviour (Apache Commons DBCP2). */
class DatabaseConfigTest {

    @BeforeAll
    static void createSchema() throws Exception {
        TestDatabase.dataSource();
    }

    @Test
    void poolIsConfiguredFromThePropertiesFile() {
        Map<String, Integer> pool = DatabaseConfig.getPoolStatistics();
        assertEquals(5, pool.get("initialSize"));
        assertEquals(5, pool.get("minIdle"));
        assertEquals(10, pool.get("maxIdle"));
        assertEquals(20, pool.get("maxTotal"));
        assertEquals(100, pool.get("maxOpenPreparedStatements"));
    }

    @Test
    void jdbcUrlIsSplitIntoHostPortAndDatabase() {
        assertEquals("student_management_test", DatabaseConfig.getDatabaseName());
        assertEquals("localhost", DatabaseConfig.getHost());
        assertEquals(3306, DatabaseConfig.getPort());
        assertEquals("jdbc:mysql://localhost:3306/?rewriteBatchedStatements=true", DatabaseConfig.getServerUrl());
    }

    @Test
    void pooledConnectionIsValidAndPointsToTheTestDatabase() throws SQLException {
        try (Connection connection = DatabaseConfig.getConnection()) {
            assertTrue(connection.isValid(2));
            assertEquals("student_management_test", connection.getCatalog());
            assertTrue(connection.getAutoCommit(), "connections start in auto-commit mode");
        }
    }

    @Test
    void closingAConnectionReturnsItToThePool() throws SQLException {
        int activeBefore = TestDatabase.activeConnections();
        List<Connection> borrowed = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                borrowed.add(DatabaseConfig.getConnection());
            }
            assertEquals(activeBefore + 3, TestDatabase.activeConnections());
        } finally {
            for (Connection connection : borrowed) {
                connection.close();
            }
        }
        assertEquals(activeBefore, TestDatabase.activeConnections());
        assertTrue(DatabaseConfig.getPoolStatistics().get("idle") >= 3, "returned connections are idle in the pool");
    }

    @Test
    void thePhysicalMySqlSessionIsReusedAfterClose() throws SQLException {
        long first = currentConnectionId();
        long second = currentConnectionId();
        assertEquals(first, second, "the pool hands out the same MySQL session again instead of reconnecting");
    }

    private static long currentConnectionId() throws SQLException {
        try (Connection connection = DatabaseConfig.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT CONNECTION_ID()")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
