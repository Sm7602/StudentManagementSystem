package com.studentmanagement.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.studentmanagement.TestDatabase;
import com.studentmanagement.dao.CourseDAO;
import com.studentmanagement.model.Course;
import com.studentmanagement.model.Enrollment;
import com.studentmanagement.model.Student;

/** Validation, transactions and batch operations of the service layer. */
class StudentServiceTest {

    private StudentService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = TestDatabase.dataSource();
        TestDatabase.resetTables();
        service = new StudentService(dataSource);
        CourseDAO courseDAO = new CourseDAO(dataSource);
        courseDAO.addCourse(new Course("CS101", "Introduction to Programming", 4, "Computer Science"));
        courseDAO.addCourse(new Course("MA101", "Calculus I", 4, "Mathematics"));
    }

    @AfterEach
    void everyConnectionWasReturnedToThePool() {
        assertEquals(0, TestDatabase.activeConnections(), "connection leak detected");
    }

    // --------------------------------------------------------------- add / CRUD

    @Test
    void addStudentNormalisesInputAndReturnsTheStoredRecord() throws SQLException {
        Student input = new Student("  Ishaan ", " Kapoor", "  Ishaan.Kapoor@Example.COM ", LocalDate.of(2004, 3, 15),
                " Computer Science ", new BigDecimal("3.45"), null);

        Student saved = service.addStudent(input);

        assertTrue(saved.getId() > 0);
        assertEquals("Ishaan", saved.getFirstName());
        assertEquals("Kapoor", saved.getLastName());
        assertEquals("ishaan.kapoor@example.com", saved.getEmail(), "e-mail is trimmed and lower-cased");
        assertEquals("Computer Science", saved.getDepartment());
        assertEquals(LocalDate.now(), saved.getEnrollmentDate(), "enrollment date defaults to today");
        assertTrue(saved.getCreatedAt() != null && saved.getUpdatedAt() != null, "re-read from the database");
    }

    static Stream<Arguments> invalidStudents() {
        LocalDate dob = LocalDate.of(2003, 5, 20);
        LocalDate enrolled = LocalDate.of(2021, 8, 1);
        return Stream.of(
                Arguments.of(new Student(" ", "Rao", "a@example.com", dob, "Physics", null, enrolled), "First name is required"),
                Arguments.of(new Student("R2D2", "Rao", "a@example.com", dob, "Physics", null, enrolled), "may only contain letters"),
                Arguments.of(new Student("Asha", "", "a@example.com", dob, "Physics", null, enrolled), "Last name is required"),
                Arguments.of(new Student("Asha", "Rao", "not-an-email", dob, "Physics", null, enrolled), "not a valid e-mail"),
                Arguments.of(new Student("Asha", "Rao", "a@example.com", null, "Physics", null, enrolled), "Date of birth is required"),
                Arguments.of(new Student("Asha", "Rao", "a@example.com", LocalDate.now().plusDays(1), "Physics", null, enrolled),
                        "Date of birth must be in the past"),
                Arguments.of(new Student("Asha", "Rao", "a@example.com", dob, " ", null, enrolled), "Department is required"),
                Arguments.of(new Student("Asha", "Rao", "a@example.com", dob, "D".repeat(51), null, enrolled), "at most 50"),
                Arguments.of(new Student("Asha", "Rao", "a@example.com", dob, "Physics", new BigDecimal("4.01"), enrolled),
                        "GPA must be between 0.00 and 4.00"),
                Arguments.of(new Student("Asha", "Rao", "a@example.com", dob, "Physics", new BigDecimal("-0.50"), enrolled),
                        "GPA must be between 0.00 and 4.00"),
                Arguments.of(new Student("Asha", "Rao", "a@example.com", dob, "Physics", new BigDecimal("3.555"), enrolled),
                        "at most 2 decimal places"),
                Arguments.of(new Student("Asha", "Rao", "a@example.com", dob, "Physics", null, LocalDate.of(2000, 1, 1)),
                        "Enrollment date must be after the date of birth"));
    }

    @ParameterizedTest(name = "[{index}] rejected with \"{1}\"")
    @MethodSource("invalidStudents")
    void invalidInputIsRejectedBeforeAnySqlIsExecuted(Student student, String expectedMessage) throws SQLException {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.addStudent(student));

        assertTrue(error.getMessage().contains(expectedMessage), "message was: " + error.getMessage());
        assertEquals(0, TestDatabase.countRows("students"));
    }

    @Test
    void allValidationProblemsAreReportedTogether() {
        Student student = new Student("", "", "bad", null, "", null, null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.addStudent(student));

        assertEquals("First name is required; Last name is required; Email 'bad' is not a valid e-mail address; "
                + "Date of birth is required; Department is required", error.getMessage());
    }

    @Test
    void updateStudentValidatesAndPersistsChanges() throws SQLException {
        Student saved = service.addStudent(TestDatabase.newStudent("Kabir", "Chopra", "kabir@example.com", "Physics", "3.12"));
        saved.setDepartment("Mathematics");
        saved.setGpa(new BigDecimal("3.50"));

        assertTrue(service.updateStudent(saved));
        assertEquals("Mathematics", service.getStudentById(saved.getId()).orElseThrow().getDepartment());

        saved.setEmail("broken");
        assertThrows(IllegalArgumentException.class, () -> service.updateStudent(saved));
        assertEquals("kabir@example.com", service.getStudentById(saved.getId()).orElseThrow().getEmail());

        Student unsaved = TestDatabase.newStudent("New", "Person", "new@example.com", "Physics", null);
        assertThrows(IllegalArgumentException.class, () -> service.updateStudent(unsaved), "id 0 cannot be updated");
    }

    @Test
    void searchNeedsAtLeastOneCriterionAndAValidGpa() {
        assertThrows(IllegalArgumentException.class, () -> service.searchStudents(" ", "", null));
        assertThrows(IllegalArgumentException.class, () -> service.searchStudents(null, null, new BigDecimal("9")));
        assertThrows(IllegalArgumentException.class, () -> service.getStudentsByDepartment(" "));
    }

    // ------------------------------------------------------------ transactions

    @Test
    void registerWithCoursesCommitsTheStudentAndAllEnrollmentsTogether() throws SQLException {
        Student saved = service.registerStudentWithCourses(
                TestDatabase.newStudent("Diya", "Malhotra", "diya@example.com", "Mathematics", "3.88"),
                List.of("ma101", " CS101 ", "MA101"));            // normalised and de-duplicated

        List<Enrollment> enrollments = service.getEnrollmentsForStudent(saved.getId());
        assertEquals(2, enrollments.size());
        assertTrue(enrollments.stream().allMatch(e -> e.getEnrollmentDate().equals(saved.getEnrollmentDate())));
        assertEquals(1, TestDatabase.countRows("students"));
    }

    @Test
    void registerWithCoursesRollsBackTheInsertedStudentWhenACourseIsUnknown() throws SQLException {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                service.registerStudentWithCourses(
                        TestDatabase.newStudent("Aryan", "Desai", "aryan@example.com", "Physics", "3.74"),
                        List.of("CS101", "XX999")));

        assertEquals("Unknown course code: XX999", error.getMessage());
        assertEquals(0, TestDatabase.countRows("students"), "the student INSERT was rolled back");
        assertEquals(0, TestDatabase.countRows("student_courses"));
        assertTrue(service.searchStudents("aryan", null, null).isEmpty());
        // InnoDB does not reuse an AUTO_INCREMENT value after a rollback: id 1 was consumed by
        // the INSERT that really executed and was then rolled back.
        Student next = service.addStudent(TestDatabase.newStudent("Next", "Student", "next@example.com", "Physics", null));
        assertEquals(2, next.getId());
    }

    @Test
    void registerWithCoursesFailsAtomicallyOnADuplicateEmail() throws SQLException {
        service.addStudent(TestDatabase.newStudent("Tanvi", "Bhatt", "tanvi@example.com", "Business", "3.31"));

        SQLException error = assertThrows(SQLException.class, () -> service.registerStudentWithCourses(
                TestDatabase.newStudent("Other", "Tanvi", "TANVI@example.com", "Business", null), List.of("CS101")));

        assertEquals(1062, error.getErrorCode());
        assertEquals(1, TestDatabase.countRows("students"));
        assertEquals(0, TestDatabase.countRows("student_courses"));
    }

    // ------------------------------------------------------------------ batches

    @Test
    void csvImportInsertsEveryRowOfTheSampleFile() throws Exception {
        int imported = service.importStudentsFromCsv(Paths.get("database/sample_students.csv"));

        assertEquals(8, imported);
        assertEquals(8, TestDatabase.countRows("students"));
        Student nisha = service.searchStudents("nisha.kulkarni", null, null).get(0);
        assertNull(nisha.getGpa(), "empty GPA column becomes NULL");
    }

    @Test
    void csvImportRejectsInvalidRowsBeforeTouchingTheDatabase() throws Exception {
        Path csv = writeCsv(
                "Asha,Rao,asha@example.com,2004-01-01,Physics,3.10,2022-08-01",
                "Bad,Email,not-an-email,2004-01-01,Physics,3.10,2022-08-01",
                "Bad,Date,bad.date@example.com,31/12/2004,Physics,3.10,2022-08-01",
                "Too,Few,columns@example.com,2004-01-01",
                "Same,Email,ASHA@example.com,2004-01-01,Physics,,2022-08-01");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.importStudentsFromCsv(csv));

        assertTrue(error.getMessage().contains("line 3: Email 'not-an-email' is not a valid e-mail address"), error.getMessage());
        assertTrue(error.getMessage().contains("line 4: dates must use the format yyyy-MM-dd"), error.getMessage());
        assertTrue(error.getMessage().contains("line 5: expected 7 values but found 4"), error.getMessage());
        assertTrue(error.getMessage().contains("line 6: e-mail asha@example.com already used on line 2"), error.getMessage());
        assertEquals(0, TestDatabase.countRows("students"), "nothing imported");
    }

    @Test
    void csvImportIsRolledBackWhenTheDatabaseRejectsARow() throws Exception {
        service.addStudent(TestDatabase.newStudent("Diya", "Malhotra", "diya.malhotra@example.com", "Mathematics", "3.88"));

        SQLException error = assertThrows(SQLException.class,
                () -> service.importStudentsFromCsv(Paths.get("database/sample_students.csv")));

        assertEquals(1062, error.getErrorCode(), "diya.malhotra@example.com exists already");
        assertEquals(1, TestDatabase.countRows("students"), "none of the 8 CSV rows was kept");
    }

    @Test
    void csvImportRequiresTheHeaderLine() throws Exception {
        Path csv = tempDir.resolve("no-header.csv");
        Files.write(csv, List.of("Asha,Rao,asha@example.com,2004-01-01,Physics,3.10,2022-08-01"), StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> service.importStudentsFromCsv(csv));
    }

    @Test
    void batchEnrollmentCommitsAllEnrollments() throws SQLException {
        int a = service.addStudent(TestDatabase.newStudent("Rohan", "Mehta", "rohan@example.com", "Mathematics", "3.40")).getId();
        int b = service.addStudent(TestDatabase.newStudent("Sneha", "Reddy", "sneha@example.com", "Mathematics", "3.80")).getId();

        assertEquals(2, service.enrollStudentsInCourse("ma101", List.of(a, b, a)));

        assertEquals(2, TestDatabase.countRows("student_courses"));
    }

    @Test
    void batchEnrollmentIsAllOrNothing() throws SQLException {
        int a = service.addStudent(TestDatabase.newStudent("Rohan", "Mehta", "rohan@example.com", "Mathematics", "3.40")).getId();

        SQLException error = assertThrows(SQLException.class, () -> service.enrollStudentsInCourse("MA101", List.of(a, 424242)));

        assertEquals(1452, error.getErrorCode(), "student 424242 does not exist");
        assertEquals(0, TestDatabase.countRows("student_courses"));
        assertThrows(IllegalArgumentException.class, () -> service.enrollStudentsInCourse("XX999", List.of(a)));
        assertThrows(IllegalArgumentException.class, () -> service.enrollStudentsInCourse("MA101", List.of()));
    }

    // ------------------------------------------------------------------ helpers

    private Path writeCsv(String... rows) throws IOException {
        Path csv = tempDir.resolve("students.csv");
        List<String> lines = Stream.concat(Stream.of(StudentService.CSV_HEADER), Stream.of(rows))
                .collect(Collectors.toList());
        Files.write(csv, lines, StandardCharsets.UTF_8);
        return csv;
    }
}
