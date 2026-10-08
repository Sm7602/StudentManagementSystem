package com.studentmanagement.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.studentmanagement.TestDatabase;
import com.studentmanagement.model.Enrollment;
import com.studentmanagement.util.DatabaseUtils;

/** CRUD, keys and batch inserts of the student_courses (enrollment) table. */
class EnrollmentDAOTest {

    private static final LocalDate DATE = LocalDate.of(2023, 1, 9);

    private DataSource dataSource;
    private EnrollmentDAO dao;
    private StudentDAO studentDAO;
    private int aarav;
    private int priya;
    private int cs101;
    private int ma101;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = TestDatabase.dataSource();
        TestDatabase.resetTables();
        dao = new EnrollmentDAO(dataSource);
        studentDAO = new StudentDAO(dataSource);
        CourseDAO courseDAO = new CourseDAO(dataSource);
        aarav = studentDAO.addStudent(TestDatabase.newStudent("Aarav", "Sharma", "aarav@example.com", "Computer Science", "3.75"));
        priya = studentDAO.addStudent(TestDatabase.newStudent("Priya", "Patel", "priya@example.com", "Computer Science", "3.92"));
        cs101 = courseDAO.addCourse(TestDatabase.newCourse("CS101", "Introduction to Programming", 4, "Computer Science"));
        ma101 = courseDAO.addCourse(TestDatabase.newCourse("MA101", "Calculus I", 4, "Mathematics"));
    }

    @AfterEach
    void everyConnectionWasReturnedToThePool() {
        assertEquals(0, TestDatabase.activeConnections(), "connection leak detected");
    }

    @Test
    void addEnrollmentAndReadItBackByCompositeKey() throws SQLException {
        dao.addEnrollment(new Enrollment(aarav, cs101, DATE, null));

        Enrollment stored = dao.getEnrollment(aarav, cs101).orElseThrow();
        assertEquals(DATE, stored.getEnrollmentDate());
        assertNull(stored.getGrade(), "no grade while the course is in progress");
        assertFalse(dao.getEnrollment(priya, cs101).isPresent());
    }

    @Test
    void enrollmentsCanBeListedByStudentByCourseAndAll() throws SQLException {
        dao.addEnrollment(new Enrollment(aarav, cs101, DATE, "A"));
        dao.addEnrollment(new Enrollment(aarav, ma101, DATE, "B+"));
        dao.addEnrollment(new Enrollment(priya, cs101, DATE, "A+"));

        assertEquals(List.of(cs101, ma101), courseIds(dao.getEnrollmentsByStudent(aarav)));
        assertEquals(List.of(aarav, priya),
                dao.getEnrollmentsByCourse(cs101).stream().map(Enrollment::getStudentId).collect(Collectors.toList()));
        assertEquals(3, dao.getAllEnrollments().size());
    }

    @Test
    void updateEnrollmentRecordsTheGrade() throws SQLException {
        dao.addEnrollment(new Enrollment(aarav, cs101, DATE, null));

        assertTrue(dao.updateEnrollment(new Enrollment(aarav, cs101, DATE, "A-")));

        assertEquals("A-", dao.getEnrollment(aarav, cs101).orElseThrow().getGrade());
        assertFalse(dao.updateEnrollment(new Enrollment(priya, ma101, DATE, "B")), "no such enrollment");
    }

    @Test
    void deleteEnrollmentRemovesOnlyThatEnrollment() throws SQLException {
        dao.addEnrollment(new Enrollment(aarav, cs101, DATE, null));
        dao.addEnrollment(new Enrollment(aarav, ma101, DATE, null));

        assertTrue(dao.deleteEnrollment(aarav, cs101));

        assertEquals(List.of(ma101), courseIds(dao.getEnrollmentsByStudent(aarav)));
        assertFalse(dao.deleteEnrollment(aarav, cs101));
    }

    @Test
    void enrollingTwiceInTheSameCourseIsRejectedByThePrimaryKey() throws SQLException {
        dao.addEnrollment(new Enrollment(aarav, cs101, DATE, null));

        SQLException error = assertThrows(SQLIntegrityConstraintViolationException.class,
                () -> dao.addEnrollment(new Enrollment(aarav, cs101, DATE, null)));
        assertEquals(1062, error.getErrorCode());
    }

    @Test
    void enrollingAnUnknownStudentIsRejectedByTheForeignKey() {
        SQLException error = assertThrows(SQLIntegrityConstraintViolationException.class,
                () -> dao.addEnrollment(new Enrollment(424242, cs101, DATE, null)));
        assertEquals(1452, error.getErrorCode());
    }

    @Test
    void anInvalidGradeIsRejectedByTheCheckConstraint() {
        SQLException error = assertThrows(SQLException.class,
                () -> dao.addEnrollment(new Enrollment(aarav, cs101, DATE, "Z")));
        assertEquals(3819, error.getErrorCode(), "chk_student_courses_grade");
    }

    @Test
    void deletingAStudentDeletesTheirEnrollments() throws SQLException {
        dao.addEnrollment(new Enrollment(aarav, cs101, DATE, null));
        dao.addEnrollment(new Enrollment(aarav, ma101, DATE, null));
        dao.addEnrollment(new Enrollment(priya, cs101, DATE, null));

        assertTrue(studentDAO.deleteStudent(aarav));

        assertTrue(dao.getEnrollmentsByStudent(aarav).isEmpty(), "ON DELETE CASCADE");
        assertEquals(1, TestDatabase.countRows("student_courses"));
    }

    @Test
    void batchInsideATransactionIsCommittedByTheCaller() throws SQLException {
        List<Enrollment> batch = List.of(new Enrollment(aarav, cs101, DATE, null),
                new Enrollment(aarav, ma101, DATE, null), new Enrollment(priya, ma101, DATE, null));

        int inserted = DatabaseUtils.executeInTransaction(dataSource, connection -> dao.addEnrollmentsBatch(connection, batch));

        assertEquals(3, inserted);
        assertEquals(3, TestDatabase.countRows("student_courses"));
    }

    @Test
    void aFailingBatchRollsBackEverythingAlreadyDoneInTheTransaction() {
        List<Enrollment> validBatch = List.of(new Enrollment(aarav, cs101, DATE, null),
                new Enrollment(priya, cs101, DATE, null));
        List<Enrollment> invalidBatch = List.of(new Enrollment(aarav, ma101, DATE, null),
                new Enrollment(424242, ma101, DATE, null));         // violates the foreign key

        SQLException error = assertThrows(SQLException.class, () ->
                DatabaseUtils.executeInTransaction(dataSource, connection -> {
                    dao.addEnrollmentsBatch(connection, validBatch);   // succeeds inside the transaction
                    return dao.addEnrollmentsBatch(connection, invalidBatch);
                }));

        assertEquals(1452, error.getErrorCode());
        assertEquals(0, countEnrollments(), "the already executed first batch was rolled back as well");
    }

    @Test
    void batchRefusesToRunOutsideATransaction() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertThrows(IllegalStateException.class,
                    () -> dao.addEnrollmentsBatch(connection, List.of(new Enrollment(aarav, cs101, DATE, null))));
        }
        assertEquals(0, countEnrollments());
    }

    private static int countEnrollments() {
        try {
            return TestDatabase.countRows("student_courses");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Integer> courseIds(List<Enrollment> enrollments) {
        return enrollments.stream().map(Enrollment::getCourseId).collect(Collectors.toList());
    }
}
