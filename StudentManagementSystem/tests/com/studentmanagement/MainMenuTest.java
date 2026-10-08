package com.studentmanagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.studentmanagement.config.DatabaseInitializer;
import com.studentmanagement.dao.StudentDAO;
import com.studentmanagement.model.Student;
import com.studentmanagement.service.ReportService;
import com.studentmanagement.service.StudentService;

/**
 * End-to-end tests of the console menu: scripted keyboard input is fed to {@link Main}
 * and both the printed output and the resulting database state are checked.
 */
class MainMenuTest {

    private DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = TestDatabase.dataSource();
        TestDatabase.resetTables();
    }

    @Test
    void everyMenuOptionWorksEndToEnd() throws SQLException {
        String output = runMenu(
                "1", "y",                                                    // initialize + sample data (ids 1-10)
                "2", "Test", "Student", "Test.Student@Example.com", "2004-05-06",
                "Computer Science", "3.30", "", "cs101, MA101",              // add with 2 courses -> id 11
                "3",                                                         // view all
                "4", "1", "11",                                              // search by id
                "4", "2", "sharma", "", "",                                  // search by keyword
                "5", "11", "", "", "", "", "Mathematics", "3.90", "",        // update department + GPA
                "6", "11", "y",                                              // delete (cascades enrollments)
                "7", "1", "",                                                // batch CSV import -> ids 12-19
                "7", "2", "CS301", "12, 13",                                 // batch enrollment
                "8", "Physics",                                              // stored procedure
                "9",                                                         // statistics
                "10");                                                       // exit

        assertContains(output, "[OK] Sample data loaded in one transaction (10 students, 6 courses, 16 enrollments)");
        assertContains(output, "[OK] Student added with ID 11 and enrolled in 2 course(s) - committed as one transaction");
        assertContains(output, "11 student(s)");
        assertContains(output, "CS101   Introduction to Programming");
        assertContains(output, "Aarav Sharma");
        assertContains(output, "1 student(s) found");
        assertContains(output, "[OK] Student 11 updated");
        assertContains(output, "  Department      : Mathematics");
        assertContains(output, "[OK] Student 11 deleted (2 enrollment(s) removed by ON DELETE CASCADE)");
        assertContains(output, "[OK] Batch committed: 8 student(s) inserted with addBatch()/executeBatch()");
        assertContains(output, "[OK] Batch committed: 2 enrollment(s) created for CS301");
        assertContains(output, "Executing {CALL GetStudentsByDepartment(?)} with ? = 'Physics'");
        assertContains(output, "4 student(s) returned by the stored procedure");
        assertContains(output, "Record counts (stored procedure GetDatabaseStatistics, OUT parameters)");
        assertContains(output, "Goodbye!");

        assertEquals(18, TestDatabase.countRows("students"), "10 sample + 8 imported (student 11 deleted)");
        assertEquals(16 + 2, TestDatabase.countRows("student_courses"));
        assertTrue(!new StudentDAO(dataSource).getStudentById(11).isPresent());
        assertEquals(0, TestDatabase.activeConnections(), "no connection leaked by the whole session");
    }

    @Test
    void invalidInputIsRejectedWithHelpfulMessagesAndNothingIsSaved() throws SQLException {
        String output = runMenu(
                "abc", "0", "11",                                  // invalid menu choices
                "2",
                "", "Asha",                                        // first name required
                "Rao1", "Rao",                                     // digits not allowed
                "not-an-email", "asha.rao@example.com",
                "31/12/2004", "2030-01-01", "2004-12-31",           // bad format, future date, valid
                "", "Mathematics",
                "5", "abc", "3.5",                                 // GPA out of range, not a number, valid
                "2000-01-01", "",                                  // enrollment before birth, then today
                "XX999",                                           // unknown course -> rollback
                "6", "999",                                        // delete unknown id
                "7", "1", "database/no-such-file.csv",             // missing CSV
                "10");

        assertContains(output, "Please enter a number between 1 and 10.");
        assertContains(output, "First name is required");
        assertContains(output, "Last name may only contain letters, spaces, apostrophes, dots and hyphens");
        assertContains(output, "Email 'not-an-email' is not a valid e-mail address");
        assertContains(output, "'31/12/2004' is not a valid date - use the format yyyy-MM-dd");
        assertContains(output, "Date of birth must be in the past");
        assertContains(output, "Department is required");
        assertContains(output, "GPA must be between 0.00 and 4.00");
        assertContains(output, "'abc' is not a number");
        assertContains(output, "Enrollment date must be after the date of birth");
        assertContains(output, "Transaction rolled back - the student was NOT saved.");
        assertContains(output, "Validation error: Unknown course code: XX999");
        assertContains(output, "No student found with ID 999");
        assertContains(output, "ERROR: CSV file not found: database/no-such-file.csv");
        assertEquals(0, TestDatabase.countRows("students"));
    }

    @Test
    void databaseErrorsAreExplainedAndTheMenuKeepsRunning() throws SQLException {
        new StudentDAO(dataSource).addStudent(
                TestDatabase.newStudent("Aarav", "Sharma", "aarav.sharma@example.com", "Computer Science", "3.75"));

        String output = runMenu(
                "2", "Copy", "Cat", "AARAV.SHARMA@example.com", "2003-01-01", "Physics", "", "", "",  // duplicate e-mail
                "3");                                                                               // input ends here

        assertContains(output, "ERROR: A record with the same unique value already exists");
        assertContains(output, "Duplicate entry 'aarav.sharma@example.com' for key 'students.uk_students_email'");
        assertContains(output, "SQLState 23000, error 1062");
        assertContains(output, "1 student(s)");                    // option 3 still works afterwards
        assertContains(output, "Input closed - exiting.");
        assertEquals(1, TestDatabase.countRows("students"));
    }

    @Test
    void updateKeepsValuesWhenEnterIsPressed() throws SQLException {
        Student original = TestDatabase.newStudent("Kabir", "Chopra", "kabir@example.com", "Physics", "3.12");
        new StudentDAO(dataSource).addStudent(original);

        runMenu("5", "1", "", "", "", "", "", "", "", "10");

        Student after = new StudentDAO(dataSource).getStudentById(1).orElseThrow();
        assertEquals("Kabir", after.getFirstName());
        assertEquals("kabir@example.com", after.getEmail());
        assertEquals("Physics", after.getDepartment());
        assertEquals(0, original.getGpa().compareTo(after.getGpa()));
    }

    private String runMenu(String... lines) {
        byte[] input = (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        new Main(new ByteArrayInputStream(input), out, new StudentService(dataSource), new ReportService(dataSource),
                new DatabaseInitializer()).run();
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void assertContains(String output, String expected) {
        assertTrue(output.contains(expected), "expected output to contain: " + expected + "\n----- output -----\n" + output);
    }
}
