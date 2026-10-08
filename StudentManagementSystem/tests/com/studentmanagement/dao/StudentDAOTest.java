package com.studentmanagement.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.studentmanagement.TestDatabase;
import com.studentmanagement.model.Student;

/** CRUD, search, batch and stored-procedure access for the students table. */
class StudentDAOTest {

    private StudentDAO dao;

    @BeforeEach
    void setUp() throws Exception {
        dao = new StudentDAO(TestDatabase.dataSource());
        TestDatabase.resetTables();
    }

    @AfterEach
    void everyConnectionWasReturnedToThePool() {
        assertEquals(0, TestDatabase.activeConnections(), "connection leak detected");
    }

    // ------------------------------------------------------------- create / read

    @Test
    void addStudentStoresEveryFieldAndReturnsTheGeneratedId() throws SQLException {
        Student student = TestDatabase.newStudent("Aarav", "Sharma", "aarav@example.com", "Computer Science", "3.75");

        int id = dao.addStudent(student);

        assertEquals(1, id);
        assertEquals(id, student.getId());
        Student stored = dao.getStudentById(id).orElseThrow();
        assertEquals("Aarav", stored.getFirstName());
        assertEquals("Sharma", stored.getLastName());
        assertEquals("aarav@example.com", stored.getEmail());
        assertEquals(LocalDate.of(2003, 5, 20), stored.getDateOfBirth());
        assertEquals("Computer Science", stored.getDepartment());
        assertEquals(new BigDecimal("3.75"), stored.getGpa());
        assertEquals(LocalDate.of(2021, 8, 1), stored.getEnrollmentDate());
        assertNotNull(stored.getCreatedAt(), "created_at is filled by MySQL");
        assertNotNull(stored.getUpdatedAt(), "updated_at is filled by MySQL");
    }

    @Test
    void missingGpaIsStoredAsSqlNull() throws SQLException {
        int id = dao.addStudent(TestDatabase.newStudent("Meera", "Joshi", "meera@example.com", "Physics", null));
        assertNull(dao.getStudentById(id).orElseThrow().getGpa());
    }

    @Test
    void getStudentByIdReturnsEmptyForAnUnknownId() throws SQLException {
        assertFalse(dao.getStudentById(424242).isPresent());
    }

    @Test
    void getAllStudentsReturnsEveryStudentOrderedById() throws SQLException {
        dao.addStudent(TestDatabase.newStudent("Rohan", "Mehta", "rohan@example.com", "Mathematics", "3.40"));
        dao.addStudent(TestDatabase.newStudent("Ananya", "Iyer", "ananya@example.com", "Physics", "3.65"));
        dao.addStudent(TestDatabase.newStudent("Vikram", "Singh", "vikram@example.com", "Business", "2.95"));

        List<Student> all = dao.getAllStudents();

        assertEquals(List.of(1, 2, 3), all.stream().map(Student::getId).collect(Collectors.toList()));
        assertEquals(List.of("Rohan", "Ananya", "Vikram"), firstNames(all));
    }

    // ------------------------------------------------------------------- search

    @Test
    void searchMatchesPartOfFirstNameLastNameFullNameOrEmail() throws SQLException {
        dao.addStudent(TestDatabase.newStudent("Priya", "Patel", "priya.patel@example.com", "Computer Science", "3.92"));
        dao.addStudent(TestDatabase.newStudent("Rohan", "Mehta", "rohan@example.com", "Mathematics", "3.40"));
        dao.addStudent(TestDatabase.newStudent("Kavya", "Gupta", "k.gupta@example.com", "Physics", "3.55"));

        assertEquals(List.of("Priya"), firstNames(dao.searchStudents("pri", null, null)));
        assertEquals(List.of("Rohan"), firstNames(dao.searchStudents("MEHTA", null, null)), "case-insensitive");
        assertEquals(List.of("Kavya"), firstNames(dao.searchStudents("k.gupta", null, null)));
        assertEquals(List.of("Priya"), firstNames(dao.searchStudents("Priya Patel", null, null)), "full name");
        assertTrue(dao.searchStudents("nobody", null, null).isEmpty());
    }

    @Test
    void searchCombinesDepartmentAndMinimumGpa() throws SQLException {
        dao.addStudent(TestDatabase.newStudent("Aarav", "Sharma", "aarav@example.com", "Computer Science", "3.75"));
        dao.addStudent(TestDatabase.newStudent("Arjun", "Nair", "arjun@example.com", "Computer Science", "3.10"));
        dao.addStudent(TestDatabase.newStudent("Sneha", "Reddy", "sneha@example.com", "Mathematics", "3.80"));
        dao.addStudent(TestDatabase.newStudent("Meera", "Joshi", "meera@example.com", "Computer Science", null));

        // results are ordered by last name, then first name
        assertEquals(List.of("Meera", "Arjun", "Aarav"),
                firstNames(dao.searchStudents(null, "computer science", null)));
        assertEquals(List.of("Aarav"),
                firstNames(dao.searchStudents(null, "Computer Science", new BigDecimal("3.50"))));
        assertEquals(List.of("Sneha", "Aarav"),
                firstNames(dao.searchStudents(null, null, new BigDecimal("3.70"))), "NULL GPA is excluded");
        assertEquals(List.of("Arjun", "Aarav"),
                firstNames(dao.searchStudents("ar", "Computer Science", new BigDecimal("3.00"))), "all three criteria");
    }

    @Test
    void searchTreatsWildcardsAndSqlInjectionAttemptsAsPlainText() throws SQLException {
        dao.addStudent(TestDatabase.newStudent("Rahul", "Verma", "rahul@example.com", "Business", "3.20"));
        dao.addStudent(TestDatabase.newStudent("Vikram", "Singh", "vikram@example.com", "Business", "2.95"));

        assertTrue(dao.searchStudents("%", null, null).isEmpty(), "% must not act as a wildcard");
        assertTrue(dao.searchStudents("_", null, null).isEmpty(), "_ must not act as a wildcard");
        assertTrue(dao.searchStudents("' OR '1'='1", null, null).isEmpty());
        assertTrue(dao.searchStudents(null, "Business' OR 'x'='x", null).isEmpty());
        assertTrue(dao.searchStudents("x'; DROP TABLE students; --", null, null).isEmpty());
        assertEquals(2, TestDatabase.countRows("students"), "table untouched");
    }

    // ---------------------------------------------------------- update / delete

    @Test
    void updateStudentChangesTheStoredValues() throws SQLException {
        Student student = TestDatabase.newStudent("Kabir", "Chopra", "kabir@example.com", "Physics", "3.12");
        int id = dao.addStudent(student);
        student.setDepartment("Mathematics");
        student.setGpa(new BigDecimal("3.90"));
        student.setEmail("kabir.chopra@example.com");

        assertTrue(dao.updateStudent(student));

        Student stored = dao.getStudentById(id).orElseThrow();
        assertEquals("Mathematics", stored.getDepartment());
        assertEquals(new BigDecimal("3.90"), stored.getGpa());
        assertEquals("kabir.chopra@example.com", stored.getEmail());
    }

    @Test
    void updateReturnsFalseWhenTheStudentDoesNotExist() throws SQLException {
        Student ghost = TestDatabase.newStudent("Nobody", "Here", "nobody@example.com", "Physics", null);
        ghost.setId(424242);
        assertFalse(dao.updateStudent(ghost));
    }

    @Test
    void deleteStudentRemovesTheRow() throws SQLException {
        int id = dao.addStudent(TestDatabase.newStudent("Tanvi", "Bhatt", "tanvi@example.com", "Business", "3.31"));

        assertTrue(dao.deleteStudent(id));
        assertFalse(dao.getStudentById(id).isPresent());
        assertFalse(dao.deleteStudent(id), "second delete finds nothing");
    }

    // -------------------------------------------------------------- constraints

    @Test
    void duplicateEmailIsRejectedByTheUniqueConstraint() throws SQLException {
        dao.addStudent(TestDatabase.newStudent("Diya", "Malhotra", "diya@example.com", "Mathematics", "3.88"));

        SQLException error = assertThrows(SQLIntegrityConstraintViolationException.class, () ->
                dao.addStudent(TestDatabase.newStudent("Other", "Person", "DIYA@example.com", "Physics", null)));

        assertEquals(1062, error.getErrorCode(), "unique e-mail is case-insensitive (utf8mb4_unicode_ci)");
        assertEquals(1, TestDatabase.countRows("students"));
    }

    @Test
    void checkConstraintRejectsAGpaAboveFour() {
        SQLException error = assertThrows(SQLException.class, () ->
                dao.addStudent(TestDatabase.newStudent("Too", "High", "high@example.com", "Physics", "4.50")));
        assertEquals(3819, error.getErrorCode(), "chk_students_gpa");
    }

    // ------------------------------------------------------------------- batch

    @Test
    void batchInsertStoresAllRowsAndAssignsTheirIds() throws SQLException {
        List<Student> students = students(25, "batch");

        assertEquals(25, dao.addStudentsBatch(students));

        assertEquals(25, TestDatabase.countRows("students"));
        for (Student student : students) {
            assertEquals(student.getEmail(), dao.getStudentById(student.getId()).orElseThrow().getEmail());
        }
    }

    @Test
    void largeBatchesAreSentInChunksAndStillCommitTogether() throws SQLException {
        int rows = StudentDAO.BATCH_SIZE * 2 + 37;          // 3 executeBatch() calls
        List<Student> students = students(rows, "chunk");

        assertEquals(rows, dao.addStudentsBatch(students));

        assertEquals(rows, TestDatabase.countRows("students"));
        Student last = students.get(rows - 1);
        assertEquals(last.getEmail(), dao.getStudentById(last.getId()).orElseThrow().getEmail());
    }

    @Test
    void batchIsRolledBackCompletelyWhenOneRowFails() throws SQLException {
        dao.addStudent(TestDatabase.newStudent("Already", "There", "taken@example.com", "Physics", null));
        // The duplicate sits in the SECOND chunk, so the first executeBatch() (500 rows) has
        // already been executed when the failure happens - only the rollback can undo it.
        List<Student> students = students(StudentDAO.BATCH_SIZE + 10, "rollback");
        students.add(StudentDAO.BATCH_SIZE + 5,
                TestDatabase.newStudent("Duplicate", "Email", "taken@example.com", "Physics", null));

        SQLException error = assertThrows(SQLException.class, () -> dao.addStudentsBatch(students));

        assertEquals(1062, error.getErrorCode());
        assertEquals(1, TestDatabase.countRows("students"), "no row of the failed batch was kept");
    }

    // --------------------------------------------------------- stored procedure

    @Test
    void storedProcedureReturnsTheStudentsOfOneDepartmentOrderedByName() throws SQLException {
        dao.addStudent(TestDatabase.newStudent("Priya", "Patel", "priya@example.com", "Computer Science", "3.92"));
        dao.addStudent(TestDatabase.newStudent("Rohan", "Mehta", "rohan@example.com", "Mathematics", "3.40"));
        dao.addStudent(TestDatabase.newStudent("Aarav", "Sharma", "aarav@example.com", "Computer Science", "3.75"));
        dao.addStudent(TestDatabase.newStudent("Arjun", "Nair", "arjun@example.com", "Computer Science", "3.10"));

        List<Student> result = dao.getStudentsByDepartment("computer science");

        assertEquals(List.of("Nair", "Patel", "Sharma"),
                result.stream().map(Student::getLastName).collect(Collectors.toList()));
        assertTrue(result.stream().allMatch(s -> s.getId() > 0 && s.getCreatedAt() != null), "fully mapped rows");
        assertTrue(dao.getStudentsByDepartment("Astronomy").isEmpty());
    }

    @Test
    void getAllDepartmentsReturnsDistinctNamesAlphabetically() throws SQLException {
        dao.addStudent(TestDatabase.newStudent("Priya", "Patel", "priya@example.com", "Physics", null));
        dao.addStudent(TestDatabase.newStudent("Rohan", "Mehta", "rohan@example.com", "Business", null));
        dao.addStudent(TestDatabase.newStudent("Aarav", "Sharma", "aarav@example.com", "Physics", null));

        assertEquals(List.of("Business", "Physics"), dao.getAllDepartments());
    }

    // ------------------------------------------------------------------ helpers

    private static List<Student> students(int count, String prefix) {
        List<Student> students = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            students.add(TestDatabase.newStudent("Student", "Number", prefix + i + "@example.com",
                    i % 2 == 0 ? "Physics" : "Mathematics", "3.00"));
        }
        return students;
    }

    private static List<String> firstNames(List<Student> students) {
        return students.stream().map(Student::getFirstName).collect(Collectors.toList());
    }
}
