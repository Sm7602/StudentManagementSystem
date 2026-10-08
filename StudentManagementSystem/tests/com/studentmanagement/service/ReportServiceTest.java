package com.studentmanagement.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.studentmanagement.TestDatabase;
import com.studentmanagement.dao.CourseDAO;
import com.studentmanagement.dao.EnrollmentDAO;
import com.studentmanagement.dao.StudentDAO;
import com.studentmanagement.model.Enrollment;
import com.studentmanagement.model.Student;

/** Menu option 9 - statistics from the stored procedure, aggregates, pool and indexes. */
class ReportServiceTest {

    private DataSource dataSource;
    private ReportService reports;
    private StudentDAO studentDAO;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = TestDatabase.dataSource();
        TestDatabase.resetTables();
        reports = new ReportService(dataSource);
        studentDAO = new StudentDAO(dataSource);
    }

    @Test
    void totalsAreReadFromTheStoredProcedureOutParameters() throws SQLException {
        int s1 = studentDAO.addStudent(TestDatabase.newStudent("Aarav", "Sharma", "aarav@example.com", "Computer Science", "3.00"));
        studentDAO.addStudent(TestDatabase.newStudent("Priya", "Patel", "priya@example.com", "Computer Science", "4.00"));
        studentDAO.addStudent(TestDatabase.newStudent("Meera", "Joshi", "meera@example.com", "Physics", null));
        CourseDAO courseDAO = new CourseDAO(dataSource);
        int c1 = courseDAO.addCourse(TestDatabase.newCourse("CS101", "Introduction to Programming", 4, "Computer Science"));
        int c2 = courseDAO.addCourse(TestDatabase.newCourse("MA101", "Calculus I", 4, "Mathematics"));
        EnrollmentDAO enrollmentDAO = new EnrollmentDAO(dataSource);
        enrollmentDAO.addEnrollment(new Enrollment(s1, c1, LocalDate.of(2021, 8, 9), "A"));
        enrollmentDAO.addEnrollment(new Enrollment(s1, c2, LocalDate.of(2021, 8, 9), null));

        Map<String, Number> totals = reports.getDatabaseTotals();

        assertEquals(3, totals.get("students"));
        assertEquals(2, totals.get("courses"));
        assertEquals(2, totals.get("enrollments"));
        assertEquals(new BigDecimal("3.50"), totals.get("averageGpa"), "AVG ignores the NULL GPA");
    }

    @Test
    void averageGpaIsNullForAnEmptyDatabase() throws SQLException {
        Map<String, Number> totals = reports.getDatabaseTotals();

        assertEquals(0, totals.get("students"));
        assertNull(totals.get("averageGpa"));
    }

    @Test
    void studentsAreCountedAndAveragedPerDepartment() throws SQLException {
        studentDAO.addStudent(TestDatabase.newStudent("Aarav", "Sharma", "aarav@example.com", "Computer Science", "3.75"));
        studentDAO.addStudent(TestDatabase.newStudent("Arjun", "Nair", "arjun@example.com", "Computer Science", "3.10"));
        studentDAO.addStudent(TestDatabase.newStudent("Vikram", "Singh", "vikram@example.com", "Business", "2.95"));
        studentDAO.addStudent(TestDatabase.newStudent("Meera", "Joshi", "meera@example.com", "Physics", null));

        Map<String, Integer> expectedCounts = new LinkedHashMap<>();
        expectedCounts.put("Business", 1);
        expectedCounts.put("Computer Science", 2);
        expectedCounts.put("Physics", 1);
        assertEquals(expectedCounts, reports.getStudentCountByDepartment());

        Map<String, BigDecimal> averages = reports.getAverageGpaByDepartment();
        assertEquals(new BigDecimal("3.43"), averages.get("Computer Science"), "(3.75 + 3.10) / 2 = 3.425 -> 3.43");
        assertEquals(new BigDecimal("2.95"), averages.get("Business"));
        assertNull(averages.get("Physics"), "no GPA recorded in Physics");
    }

    @Test
    void enrollmentCountsIncludeCoursesWithoutStudents() throws SQLException {
        int student = studentDAO.addStudent(TestDatabase.newStudent("Rohan", "Mehta", "rohan@example.com", "Mathematics", "3.40"));
        CourseDAO courseDAO = new CourseDAO(dataSource);
        int ma101 = courseDAO.addCourse(TestDatabase.newCourse("MA101", "Calculus I", 4, "Mathematics"));
        courseDAO.addCourse(TestDatabase.newCourse("PH101", "Physics I", 4, "Physics"));
        new EnrollmentDAO(dataSource).addEnrollment(new Enrollment(student, ma101, LocalDate.of(2021, 8, 9), "B+"));

        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("MA101", 1);
        expected.put("PH101", 0);
        assertEquals(expected, reports.getEnrollmentCountByCourse());
    }

    @Test
    void connectionPoolStatusComesFromDbcp2() {
        Map<String, Integer> pool = reports.getConnectionPoolStatus();

        assertEquals(20, pool.get("maxTotal"));
        assertEquals(0, pool.get("active"), "no connection is borrowed between operations");
        assertTrue(pool.get("idle") >= 1);
    }

    @Test
    void indexDefinitionsAreReadFromInformationSchema() throws SQLException {
        List<String> indexes = reports.getIndexDefinitions();

        assertTrue(indexes.contains("students.idx_students_department_gpa (department, gpa)"), indexes.toString());
        assertTrue(indexes.contains("students.uk_students_email (email) UNIQUE"), indexes.toString());
        assertTrue(indexes.contains("student_courses.PRIMARY (student_id, course_id) UNIQUE"), indexes.toString());
        assertTrue(indexes.contains("student_courses.idx_student_courses_course (course_id)"), indexes.toString());
        assertEquals(7, indexes.size());
    }

    @Test
    void departmentLookupUsesTheCompositeIndex() throws SQLException {
        String[] departments = {"Computer Science", "Mathematics", "Physics", "Business"};
        for (int i = 0; i < 40; i++) {
            studentDAO.addStudent(TestDatabase.newStudent("Student", "Number", "student" + i + "@example.com",
                    departments[i % departments.length], "3.00"));
        }

        Map<String, String> plan = reports.explainDepartmentLookup("Physics");

        assertEquals("idx_students_department_gpa", plan.get("key"));
        assertEquals("ref", plan.get("type"), "index lookup instead of a full table scan (ALL)");
    }

    @Test
    void statisticsReportContainsEverySection() throws SQLException {
        studentDAO.addStudent(TestDatabase.newStudent("Aarav", "Sharma", "aarav@example.com", "Computer Science", "3.75"));

        String report = reports.generateStatisticsReport();

        for (String section : new String[] {"Server: MySQL", "Record counts (stored procedure GetDatabaseStatistics",
                "Students per department", "Enrollments per course", "Connection pool (Apache Commons DBCP2)",
                "Indexes (information_schema.STATISTICS)", "Index check (EXPLAIN", "Report generated in"}) {
            assertTrue(report.contains(section), "missing section: " + section);
        }
        assertTrue(report.contains("Computer Science         1   average GPA 3.75"), report);
    }

    @Test
    void serverInfoNamesTheDatabaseAndTheDriver() throws SQLException {
        String info = reports.getServerInfo();
        assertTrue(info.startsWith("MySQL "), info);
        assertTrue(info.contains("Connector/J"), info);
    }
}
