package com.studentmanagement.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.studentmanagement.TestDatabase;
import com.studentmanagement.dao.StudentDAO;

/** Menu option 1 - creation of tables, constraints, indexes, procedures and sample data. */
class DatabaseInitializerTest {

    private DataSource dataSource;
    private final DatabaseInitializer initializer = new DatabaseInitializer();

    @BeforeEach
    void setUp() throws Exception {
        dataSource = TestDatabase.dataSource();
    }

    @Test
    void createsAllTablesConstraintsIndexesAndProceduresInAnEmptyDatabase() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP PROCEDURE IF EXISTS GetStudentsByDepartment");
            statement.execute("DROP PROCEDURE IF EXISTS GetDatabaseStatistics");
            statement.execute("DROP TABLE IF EXISTS student_courses, students, courses");
        }
        assertFalse(initializer.isSchemaInitialized());

        List<String> steps = initializer.initializeDatabase(false);

        assertEquals(3, steps.size());
        assertTrue(initializer.isSchemaInitialized());
        assertEquals(Set.of("PRIMARY", "uk_students_email", "idx_students_department_gpa"), indexes("students"));
        assertEquals(Set.of("PRIMARY", "uk_courses_code"), indexes("courses"));
        assertEquals(Set.of("PRIMARY", "idx_student_courses_course"), indexes("student_courses"));
        assertEquals(Set.of("chk_courses_credits", "chk_student_courses_grade", "chk_students_dates", "chk_students_gpa"),
                names("SELECT constraint_name FROM information_schema.check_constraints WHERE constraint_schema = ?"));
        assertEquals("CASCADE", deleteRule("fk_student_courses_student"));
        assertEquals("RESTRICT", deleteRule("fk_student_courses_course"));
    }

    @Test
    void runningTheInitializationAgainKeepsExistingData() throws Exception {
        TestDatabase.resetTables();
        new StudentDAO(dataSource).addStudent(
                TestDatabase.newStudent("Asha", "Rao", "asha.rao@example.com", "Physics", "3.10"));

        initializer.initializeDatabase(false);

        assertEquals(1, TestDatabase.countRows("students"));
        assertTrue(initializer.isSchemaInitialized());
    }

    @Test
    void sampleDataIsLoadedOnlyWhenTheDatabaseIsEmpty() throws Exception {
        TestDatabase.resetTables();

        List<String> firstRun = initializer.initializeDatabase(true);
        assertEquals("Sample data loaded in one transaction (10 students, 6 courses, 16 enrollments)", firstRun.get(3));
        assertEquals(10, TestDatabase.countRows("students"));
        assertEquals(6, TestDatabase.countRows("courses"));
        assertEquals(16, TestDatabase.countRows("student_courses"));

        List<String> secondRun = initializer.initializeDatabase(true);
        assertEquals("Sample data skipped: the database already contains data", secondRun.get(3));
        assertEquals(10, TestDatabase.countRows("students"));
    }

    private Set<String> indexes(String table) throws SQLException {
        Set<String> names = new TreeSet<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT DISTINCT index_name "
                     + "FROM information_schema.statistics WHERE table_schema = ? AND table_name = ?")) {
            statement.setString(1, DatabaseConfig.getDatabaseName());
            statement.setString(2, table);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }

    private Set<String> names(String sql) throws SQLException {
        Set<String> names = new TreeSet<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, DatabaseConfig.getDatabaseName());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }

    private String deleteRule(String foreignKey) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT delete_rule FROM "
                     + "information_schema.referential_constraints WHERE constraint_schema = ? AND constraint_name = ?")) {
            statement.setString(1, DatabaseConfig.getDatabaseName());
            statement.setString(2, foreignKey);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
