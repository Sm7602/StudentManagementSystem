package com.studentmanagement.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.studentmanagement.TestDatabase;
import com.studentmanagement.config.DatabaseConfig;
import com.studentmanagement.config.DatabaseInitializer;
import com.studentmanagement.dao.CourseDAO;
import com.studentmanagement.dao.EnrollmentDAO;
import com.studentmanagement.dao.StudentDAO;
import com.studentmanagement.model.Enrollment;
import com.studentmanagement.model.Student;

/**
 * Backup and recovery with the MySQL client tools: back up, simulate the loss of every
 * table and procedure, restore, and verify that data and procedures are back.
 * Skipped (not failed) when mysqldump / mysql are not installed.
 */
class BackupRecoveryTest {

    private DataSource dataSource;

    @TempDir
    Path backupDirectory;

    @BeforeAll
    static void requireMySqlClientTools() {
        assumeTrue(toolRuns(DatabaseConfig.getMysqldumpPath()) && toolRuns(DatabaseConfig.getMysqlPath()),
                "mysqldump/mysql not found - set backup.mysqldumpPath / backup.mysqlPath to run this test");
    }

    @BeforeEach
    void setUp() throws Exception {
        dataSource = TestDatabase.dataSource();
        TestDatabase.resetTables();
    }

    @Test
    void backupThenTotalLossThenRestoreRecoversDataAndProcedures() throws Exception {
        StudentDAO studentDAO = new StudentDAO(dataSource);
        int aarav = studentDAO.addStudent(TestDatabase.newStudent("Aarav", "Sharma", "aarav@example.com", "Computer Science", "3.75"));
        studentDAO.addStudent(TestDatabase.newStudent("Priya", "Patel", "priya@example.com", "Computer Science", "3.92"));
        studentDAO.addStudent(TestDatabase.newStudent("Rohan", "O'Brien", "rohan@example.com", "Mathematics", null));
        int cs101 = new CourseDAO(dataSource).addCourse(TestDatabase.newCourse("CS101", "Introduction to Programming", 4, "Computer Science"));
        new EnrollmentDAO(dataSource).addEnrollment(new Enrollment(aarav, cs101, LocalDate.of(2021, 8, 9), "A"));

        // 1. backup
        Path backup = DatabaseUtils.backupDatabase(backupDirectory);
        String dump = new String(Files.readAllBytes(backup), StandardCharsets.UTF_8);
        assertTrue(backup.getFileName().toString().matches("student_management_test_\\d{8}_\\d{6}_\\d{3}\\.sql"));
        assertTrue(dump.contains("CREATE DATABASE /*!32312 IF NOT EXISTS*/ `student_management_test`"), "database definition");
        assertTrue(dump.contains("CREATE TABLE `students`"), "table definitions");
        assertTrue(dump.contains("INSERT INTO `students`"), "table data");
        assertTrue(dump.contains("PROCEDURE `GetStudentsByDepartment`"), "stored procedures");

        // 2. disaster: every table and procedure is gone
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE student_courses, students, courses");
            statement.execute("DROP PROCEDURE GetStudentsByDepartment");
            statement.execute("DROP PROCEDURE GetDatabaseStatistics");
        }
        assertFalse(new DatabaseInitializer().isSchemaInitialized());

        // 3. recovery
        DatabaseUtils.restoreDatabase(backup);

        assertTrue(new DatabaseInitializer().isSchemaInitialized());
        assertEquals(3, TestDatabase.countRows("students"));
        assertEquals(1, TestDatabase.countRows("courses"));
        assertEquals(1, TestDatabase.countRows("student_courses"));
        Student restored = studentDAO.getStudentById(aarav).orElseThrow();
        assertEquals("aarav@example.com", restored.getEmail(), "same primary keys after the restore");
        assertEquals("O'Brien", studentDAO.searchStudents("rohan", null, null).get(0).getLastName(), "quotes survive");
        List<String> viaProcedure = studentDAO.getStudentsByDepartment("Computer Science").stream()
                .map(Student::getFirstName).collect(Collectors.toList());
        assertEquals(List.of("Priya", "Aarav"), viaProcedure, "restored procedure works");
    }

    @Test
    void restoreRejectsAMissingFile() {
        assertThrows(FileNotFoundException.class,
                () -> DatabaseUtils.restoreDatabase(backupDirectory.resolve("does-not-exist.sql")));
    }

    @Test
    void restoreReportsMySqlErrorsAndKeepsCurrentData() throws Exception {
        new StudentDAO(dataSource).addStudent(TestDatabase.newStudent("Asha", "Rao", "asha@example.com", "Physics", null));
        Path broken = backupDirectory.resolve("broken.sql");
        Files.write(broken, "USE student_management_test;\nTHIS IS NOT SQL;\n".getBytes(StandardCharsets.UTF_8));

        Exception error = assertThrows(java.io.IOException.class, () -> DatabaseUtils.restoreDatabase(broken));

        assertTrue(error.getMessage().contains("failed with exit code"), error.getMessage());
        assertEquals(1, TestDatabase.countRows("students"));
    }

    private static boolean toolRuns(String tool) {
        try {
            Process process = new ProcessBuilder(tool, "--version").redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (java.io.IOException e) {
            return false;                     // tool not installed -> the test is skipped
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
