package com.studentmanagement.service;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.sql.DataSource;

import com.studentmanagement.config.DatabaseConfig;

/**
 * Read-only reports for menu option 9 (Database Statistics).
 * Every figure is queried from MySQL or the connection pool at the moment the report
 * is generated - nothing is estimated or hard-coded.
 */
public class ReportService {

    private static final String CALL_GET_DATABASE_STATISTICS = "{CALL GetDatabaseStatistics(?, ?, ?, ?)}";

    private static final String STUDENTS_PER_DEPARTMENT_SQL =
            "SELECT department, COUNT(*) FROM students GROUP BY department ORDER BY department";
    private static final String AVERAGE_GPA_PER_DEPARTMENT_SQL =
            "SELECT department, ROUND(AVG(gpa), 2) FROM students GROUP BY department ORDER BY department";
    private static final String ENROLLMENTS_PER_COURSE_SQL =
            "SELECT c.course_code, COUNT(sc.student_id) FROM courses c "
            + "LEFT JOIN student_courses sc ON sc.course_id = c.id "
            + "GROUP BY c.id, c.course_code ORDER BY c.course_code";
    private static final String INDEXES_SQL =
            "SELECT table_name, index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ', '), "
            + "MIN(non_unique) FROM information_schema.statistics WHERE table_schema = ? "
            + "GROUP BY table_name, index_name ORDER BY table_name, index_name";
    private static final String EXPLAIN_DEPARTMENT_LOOKUP_SQL =
            "EXPLAIN SELECT id, first_name, last_name FROM students WHERE department = ?";

    private final DataSource dataSource;

    public ReportService(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** MySQL server and JDBC driver versions, e.g. "MySQL 8.0.46 (MySQL Connector/J 9.7.0)". */
    public String getServerInfo() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            // Connector/J reports e.g. "mysql-connector-j-9.7.0 (Revision: ...)"
            String driverVersion = metaData.getDriverVersion()
                    .replaceAll(" \\(.*", "")
                    .replaceFirst("^mysql-connector-j-", "");
            return metaData.getDatabaseProductName() + " " + metaData.getDatabaseProductVersion()
                    + " (" + metaData.getDriverName() + " " + driverVersion + ")";
        }
    }

    /**
     * Calls the stored procedure GetDatabaseStatistics and reads its four OUT parameters.
     *
     * @return keys "students", "courses", "enrollments" (Integer) and "averageGpa"
     *         (BigDecimal, null when no student has a GPA)
     */
    public Map<String, Number> getDatabaseTotals() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             CallableStatement statement = connection.prepareCall(CALL_GET_DATABASE_STATISTICS)) {
            statement.registerOutParameter(1, Types.INTEGER);
            statement.registerOutParameter(2, Types.INTEGER);
            statement.registerOutParameter(3, Types.INTEGER);
            statement.registerOutParameter(4, Types.DECIMAL);
            statement.execute();
            Map<String, Number> totals = new LinkedHashMap<>();
            totals.put("students", statement.getInt(1));
            totals.put("courses", statement.getInt(2));
            totals.put("enrollments", statement.getInt(3));
            totals.put("averageGpa", statement.getBigDecimal(4));
            return totals;
        }
    }

    /** Number of students per department, alphabetically. */
    public Map<String, Integer> getStudentCountByDepartment() throws SQLException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(STUDENTS_PER_DEPARTMENT_SQL);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                counts.put(rs.getString(1), rs.getInt(2));
            }
        }
        return counts;
    }

    /** Average GPA per department (null when no student of the department has a GPA). */
    public Map<String, BigDecimal> getAverageGpaByDepartment() throws SQLException {
        Map<String, BigDecimal> averages = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(AVERAGE_GPA_PER_DEPARTMENT_SQL);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                averages.put(rs.getString(1), rs.getBigDecimal(2));
            }
        }
        return averages;
    }

    /** Number of enrolled students per course code (courses without students included). */
    public Map<String, Integer> getEnrollmentCountByCourse() throws SQLException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(ENROLLMENTS_PER_COURSE_SQL);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                counts.put(rs.getString(1), rs.getInt(2));
            }
        }
        return counts;
    }

    /** Pool configuration and current usage reported by DBCP2. */
    public Map<String, Integer> getConnectionPoolStatus() {
        return DatabaseConfig.getPoolStatistics();
    }

    /**
     * Index definitions of the application schema from information_schema.STATISTICS,
     * e.g. "students.idx_students_department_gpa (department, gpa)".
     */
    public List<String> getIndexDefinitions() throws SQLException {
        List<String> indexes = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INDEXES_SQL)) {
            statement.setString(1, DatabaseConfig.getDatabaseName());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    indexes.add(rs.getString(1) + "." + rs.getString(2) + " (" + rs.getString(3) + ")"
                            + (rs.getInt(4) == 0 ? " UNIQUE" : ""));
                }
            }
        }
        return indexes;
    }

    /**
     * Runs EXPLAIN for the department lookup performed by GetStudentsByDepartment and
     * returns the optimizer's plan: keys "type", "possible_keys", "key", "rows", "Extra".
     */
    public Map<String, String> explainDepartmentLookup(String department) throws SQLException {
        Map<String, String> plan = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(EXPLAIN_DEPARTMENT_LOOKUP_SQL)) {
            statement.setString(1, department);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    for (String column : new String[] {"type", "possible_keys", "key", "rows", "Extra"}) {
                        plan.put(column, rs.getString(column));
                    }
                }
            }
        }
        return plan;
    }

    /** Builds the complete text report shown by menu option 9. */
    public String generateStatisticsReport() throws SQLException {
        long start = System.nanoTime();
        Map<String, Number> totals = getDatabaseTotals();
        Map<String, Integer> perDepartment = getStudentCountByDepartment();
        Map<String, BigDecimal> gpaPerDepartment = getAverageGpaByDepartment();
        Map<String, Integer> perCourse = getEnrollmentCountByCourse();
        List<String> indexes = getIndexDefinitions();
        String sampleDepartment = perDepartment.isEmpty() ? null : perDepartment.keySet().iterator().next();
        Map<String, String> plan = sampleDepartment == null ? null : explainDepartmentLookup(sampleDepartment);
        Map<String, Integer> pool = getConnectionPoolStatus();
        String server = getServerInfo();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        StringBuilder report = new StringBuilder();
        report.append("Server: ").append(server).append('\n');
        report.append("Database: ").append(DatabaseConfig.getDatabaseName()).append("\n\n");

        report.append("Record counts (stored procedure GetDatabaseStatistics, OUT parameters)\n");
        report.append(String.format("  %-22s %d%n", "Students", totals.get("students").intValue()));
        report.append(String.format("  %-22s %d%n", "Courses", totals.get("courses").intValue()));
        report.append(String.format("  %-22s %d%n", "Enrollments", totals.get("enrollments").intValue()));
        report.append(String.format("  %-22s %s%n%n", "Average GPA", format(totals.get("averageGpa"))));

        report.append("Students per department\n");
        if (perDepartment.isEmpty()) {
            report.append("  (no students)\n");
        }
        for (Map.Entry<String, Integer> entry : perDepartment.entrySet()) {
            report.append(String.format("  %-22s %3d   average GPA %s%n", entry.getKey(), entry.getValue(),
                    format(gpaPerDepartment.get(entry.getKey()))));
        }

        report.append("\nEnrollments per course\n");
        if (perCourse.isEmpty()) {
            report.append("  (no courses)\n");
        }
        for (Map.Entry<String, Integer> entry : perCourse.entrySet()) {
            report.append(String.format("  %-22s %3d%n", entry.getKey(), entry.getValue()));
        }

        report.append("\nConnection pool (Apache Commons DBCP2)\n");
        report.append(String.format("  %-22s %d%n", "Active connections", pool.get("active")));
        report.append(String.format("  %-22s %d%n", "Idle connections", pool.get("idle")));
        report.append(String.format("  %-22s %d / %d%n", "Min idle / Max idle", pool.get("minIdle"), pool.get("maxIdle")));
        report.append(String.format("  %-22s %d%n", "Max total", pool.get("maxTotal")));
        report.append(String.format("  %-22s %d%n", "Max cached statements", pool.get("maxOpenPreparedStatements")));

        report.append("\nIndexes (information_schema.STATISTICS)\n");
        for (String index : indexes) {
            report.append("  ").append(index).append('\n');
        }
        if (plan != null && !plan.isEmpty()) {
            report.append(String.format("%nIndex check (EXPLAIN for department = '%s'): access type %s, key %s%n",
                    sampleDepartment, plan.get("type"), plan.get("key") == null ? "none (full scan)" : plan.get("key")));
        }
        report.append(String.format("%nReport generated in %d ms (measured)%n", elapsedMillis));
        return report.toString();
    }

    private static String format(Number value) {
        return value == null ? "n/a" : value.toString();
    }
}
