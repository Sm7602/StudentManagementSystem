package com.studentmanagement.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

import com.studentmanagement.model.Enrollment;

/**
 * Data access for the {@code student_courses} table (enrollment relationship).
 * The primary key is (student_id, course_id); foreign keys guarantee that both
 * the student and the course exist.
 */
public class EnrollmentDAO {

    private static final String COLUMN_LIST = "student_id, course_id, enrollment_date, grade";

    private static final String INSERT_SQL =
            "INSERT INTO student_courses (student_id, course_id, enrollment_date, grade) VALUES (?, ?, ?, ?)";
    private static final String SELECT_ONE_SQL =
            "SELECT " + COLUMN_LIST + " FROM student_courses WHERE student_id = ? AND course_id = ?";
    private static final String SELECT_BY_STUDENT_SQL =
            "SELECT " + COLUMN_LIST + " FROM student_courses WHERE student_id = ? ORDER BY course_id";
    private static final String SELECT_BY_COURSE_SQL =
            "SELECT " + COLUMN_LIST + " FROM student_courses WHERE course_id = ? ORDER BY student_id";
    private static final String SELECT_ALL_SQL =
            "SELECT " + COLUMN_LIST + " FROM student_courses ORDER BY student_id, course_id";
    private static final String UPDATE_SQL =
            "UPDATE student_courses SET enrollment_date = ?, grade = ? WHERE student_id = ? AND course_id = ?";
    private static final String DELETE_SQL = "DELETE FROM student_courses WHERE student_id = ? AND course_id = ?";

    /** Rows sent to the server per executeBatch() call. */
    static final int BATCH_SIZE = 500;

    private final DataSource dataSource;

    public EnrollmentDAO(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** Enrolls a student in a course. */
    public void addEnrollment(Enrollment enrollment) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            bindInsert(statement, enrollment);
            statement.executeUpdate();
        }
    }

    /**
     * Inserts many enrollments with JDBC batching on a connection whose transaction is
     * managed by the caller (see {@code StudentService}). Nothing is committed here, so the
     * caller can commit the batch together with its other statements - or roll everything back.
     *
     * @return number of enrollments inserted
     * @throws IllegalStateException if the connection is in auto-commit mode
     */
    public int addEnrollmentsBatch(Connection connection, List<Enrollment> enrollments) throws SQLException {
        if (connection.getAutoCommit()) {
            throw new IllegalStateException("addEnrollmentsBatch must run inside a transaction (auto-commit off)");
        }
        int inserted = 0;
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            int queued = 0;
            for (Enrollment enrollment : enrollments) {
                bindInsert(statement, enrollment);
                statement.addBatch();
                if (++queued == BATCH_SIZE) {
                    inserted += executeBatch(statement);
                    queued = 0;
                }
            }
            if (queued > 0) {
                inserted += executeBatch(statement);
            }
        }
        return inserted;
    }

    private static int executeBatch(PreparedStatement statement) throws SQLException {
        int[] updateCounts = statement.executeBatch();
        for (int count : updateCounts) {
            if (count == Statement.EXECUTE_FAILED) {
                throw new SQLException("A statement in the batch failed");
            }
        }
        return updateCounts.length;
    }

    /** Finds one enrollment by its composite key. */
    public Optional<Enrollment> getEnrollment(int studentId, int courseId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_ONE_SQL)) {
            statement.setInt(1, studentId);
            statement.setInt(2, courseId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    /** All enrollments of one student (uses the primary key's leftmost column). */
    public List<Enrollment> getEnrollmentsByStudent(int studentId) throws SQLException {
        return queryList(SELECT_BY_STUDENT_SQL, studentId);
    }

    /** All enrollments of one course (uses idx_student_courses_course). */
    public List<Enrollment> getEnrollmentsByCourse(int courseId) throws SQLException {
        return queryList(SELECT_BY_COURSE_SQL, courseId);
    }

    /** Every enrollment, ordered by student and course. */
    public List<Enrollment> getAllEnrollments() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_ALL_SQL);
             ResultSet rs = statement.executeQuery()) {
            return mapRows(rs);
        }
    }

    /**
     * Updates the enrollment date and grade of an existing enrollment.
     *
     * @return true if the enrollment exists
     */
    public boolean updateEnrollment(Enrollment enrollment) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(UPDATE_SQL)) {
            statement.setObject(1, enrollment.getEnrollmentDate());
            statement.setString(2, enrollment.getGrade());
            statement.setInt(3, enrollment.getStudentId());
            statement.setInt(4, enrollment.getCourseId());
            return statement.executeUpdate() == 1;
        }
    }

    /**
     * Removes a student from a course.
     *
     * @return true if the enrollment existed
     */
    public boolean deleteEnrollment(int studentId, int courseId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(DELETE_SQL)) {
            statement.setInt(1, studentId);
            statement.setInt(2, courseId);
            return statement.executeUpdate() == 1;
        }
    }

    private List<Enrollment> queryList(String sql, int id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return mapRows(rs);
            }
        }
    }

    private static void bindInsert(PreparedStatement statement, Enrollment enrollment) throws SQLException {
        statement.setInt(1, enrollment.getStudentId());
        statement.setInt(2, enrollment.getCourseId());
        statement.setObject(3, enrollment.getEnrollmentDate());   // LocalDate -> DATE
        statement.setString(4, enrollment.getGrade());            // null -> SQL NULL
    }

    private static List<Enrollment> mapRows(ResultSet rs) throws SQLException {
        List<Enrollment> enrollments = new ArrayList<>();
        while (rs.next()) {
            enrollments.add(mapRow(rs));
        }
        return enrollments;
    }

    private static Enrollment mapRow(ResultSet rs) throws SQLException {
        return new Enrollment(
                rs.getInt("student_id"),
                rs.getInt("course_id"),
                rs.getObject("enrollment_date", LocalDate.class),
                rs.getString("grade"));
    }
}
