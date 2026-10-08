package com.studentmanagement.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.studentmanagement.model.Course;
import com.studentmanagement.model.Enrollment;

/** CRUD operations and constraints of the courses table. */
class CourseDAOTest {

    private DataSource dataSource;
    private CourseDAO dao;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = TestDatabase.dataSource();
        dao = new CourseDAO(dataSource);
        TestDatabase.resetTables();
    }

    @AfterEach
    void everyConnectionWasReturnedToThePool() {
        assertEquals(0, TestDatabase.activeConnections(), "connection leak detected");
    }

    @Test
    void addCourseStoresEveryFieldAndReturnsTheGeneratedId() throws SQLException {
        Course course = TestDatabase.newCourse("CS101", "Introduction to Programming", 4, "Computer Science");

        int id = dao.addCourse(course);

        assertEquals(1, id);
        Course stored = dao.getCourseById(id).orElseThrow();
        assertEquals("CS101", stored.getCourseCode());
        assertEquals("Introduction to Programming", stored.getCourseName());
        assertEquals(4, stored.getCredits());
        assertEquals("Computer Science", stored.getDepartment());
        assertNotNull(stored.getCreatedAt());
    }

    @Test
    void courseCanBeFoundByCodeIgnoringCase() throws SQLException {
        dao.addCourse(TestDatabase.newCourse("MA101", "Calculus I", 4, "Mathematics"));

        assertEquals("Calculus I", dao.getCourseByCode("ma101").orElseThrow().getCourseName());
        assertFalse(dao.getCourseByCode("XX999").isPresent());
        assertFalse(dao.getCourseById(424242).isPresent());
    }

    @Test
    void getAllCoursesIsOrderedByCode() throws SQLException {
        dao.addCourse(TestDatabase.newCourse("PH101", "Physics I", 4, "Physics"));
        dao.addCourse(TestDatabase.newCourse("BA101", "Principles of Management", 3, "Business"));
        dao.addCourse(TestDatabase.newCourse("CS201", "Data Structures", 4, "Computer Science"));

        assertEquals(List.of("BA101", "CS201", "PH101"),
                dao.getAllCourses().stream().map(Course::getCourseCode).collect(Collectors.toList()));
    }

    @Test
    void updateCourseChangesTheStoredValues() throws SQLException {
        Course course = TestDatabase.newCourse("CS301", "Databases", 3, "Computer Science");
        int id = dao.addCourse(course);
        course.setCourseName("Database Management Systems");
        course.setCredits(4);

        assertTrue(dao.updateCourse(course));

        Course stored = dao.getCourseById(id).orElseThrow();
        assertEquals("Database Management Systems", stored.getCourseName());
        assertEquals(4, stored.getCredits());
    }

    @Test
    void deleteCourseRemovesTheRow() throws SQLException {
        int id = dao.addCourse(TestDatabase.newCourse("BA101", "Principles of Management", 3, "Business"));

        assertTrue(dao.deleteCourse(id));
        assertFalse(dao.getCourseById(id).isPresent());
        assertFalse(dao.deleteCourse(id));
    }

    @Test
    void duplicateCourseCodeIsRejected() throws SQLException {
        dao.addCourse(TestDatabase.newCourse("CS101", "Introduction to Programming", 4, "Computer Science"));

        SQLException error = assertThrows(SQLIntegrityConstraintViolationException.class, () ->
                dao.addCourse(TestDatabase.newCourse("CS101", "Another Course", 3, "Computer Science")));
        assertEquals(1062, error.getErrorCode());
    }

    @Test
    void creditsOutsideOneToTenAreRejectedByTheCheckConstraint() {
        SQLException error = assertThrows(SQLException.class, () ->
                dao.addCourse(TestDatabase.newCourse("XX100", "Too Many Credits", 12, "Physics")));
        assertEquals(3819, error.getErrorCode(), "chk_courses_credits");
    }

    @Test
    void aCourseWithEnrolledStudentsCannotBeDeleted() throws SQLException {
        int courseId = dao.addCourse(TestDatabase.newCourse("PH101", "Physics I", 4, "Physics"));
        int studentId = new StudentDAO(dataSource).addStudent(
                TestDatabase.newStudent("Ananya", "Iyer", "ananya@example.com", "Physics", "3.65"));
        new EnrollmentDAO(dataSource).addEnrollment(new Enrollment(studentId, courseId, LocalDate.of(2022, 8, 8), "A-"));

        SQLException error = assertThrows(SQLIntegrityConstraintViolationException.class, () -> dao.deleteCourse(courseId));

        assertEquals(1451, error.getErrorCode(), "fk_student_courses_course is ON DELETE RESTRICT");
        assertTrue(dao.getCourseById(courseId).isPresent());
    }
}
