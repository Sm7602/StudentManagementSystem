package com.studentmanagement.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

import com.studentmanagement.model.Course;

/**
 * Data access for the {@code courses} table (create, read, update, delete).
 * All statements are parameterised PreparedStatements closed with try-with-resources.
 */
public class CourseDAO {

    private static final String COLUMN_LIST =
            "id, course_code, course_name, credits, department, created_at, updated_at";

    private static final String INSERT_SQL =
            "INSERT INTO courses (course_code, course_name, credits, department) VALUES (?, ?, ?, ?)";
    private static final String SELECT_BY_ID_SQL = "SELECT " + COLUMN_LIST + " FROM courses WHERE id = ?";
    private static final String SELECT_BY_CODE_SQL = "SELECT " + COLUMN_LIST + " FROM courses WHERE course_code = ?";
    private static final String SELECT_ALL_SQL = "SELECT " + COLUMN_LIST + " FROM courses ORDER BY course_code";
    private static final String UPDATE_SQL =
            "UPDATE courses SET course_code = ?, course_name = ?, credits = ?, department = ? WHERE id = ?";
    private static final String DELETE_SQL = "DELETE FROM courses WHERE id = ?";

    private final DataSource dataSource;

    public CourseDAO(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * Inserts a course and stores the generated id in the object.
     *
     * @return the generated id
     */
    public int addCourse(Course course) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {
            bindCourse(statement, course);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("The insert succeeded but no generated id was returned");
                }
                course.setId(keys.getInt(1));
            }
        }
        return course.getId();
    }

    /** Finds a course by primary key. */
    public Optional<Course> getCourseById(int id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_BY_ID_SQL)) {
            statement.setInt(1, id);
            return querySingle(statement);
        }
    }

    /** Finds a course by its unique code (case-insensitive). */
    public Optional<Course> getCourseByCode(String courseCode) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return getCourseByCode(connection, courseCode);
        }
    }

    /** Finds a course by code on the given connection (used inside transactions). */
    public Optional<Course> getCourseByCode(Connection connection, String courseCode) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_BY_CODE_SQL)) {
            statement.setString(1, courseCode);
            return querySingle(statement);
        }
    }

    /** Returns all courses ordered by course code. */
    public List<Course> getAllCourses() throws SQLException {
        List<Course> courses = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_ALL_SQL);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                courses.add(mapRow(rs));
            }
        }
        return courses;
    }

    /**
     * Updates the course with {@code course.getId()}.
     *
     * @return true if the course exists
     */
    public boolean updateCourse(Course course) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(UPDATE_SQL)) {
            bindCourse(statement, course);
            statement.setInt(5, course.getId());
            return statement.executeUpdate() == 1;
        }
    }

    /**
     * Deletes a course. Fails with an integrity-constraint SQLException (MySQL error 1451)
     * while students are still enrolled ({@code ON DELETE RESTRICT}).
     *
     * @return true if a course was deleted
     */
    public boolean deleteCourse(int id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(DELETE_SQL)) {
            statement.setInt(1, id);
            return statement.executeUpdate() == 1;
        }
    }

    private static Optional<Course> querySingle(PreparedStatement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery()) {
            return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
        }
    }

    private static void bindCourse(PreparedStatement statement, Course course) throws SQLException {
        statement.setString(1, course.getCourseCode());
        statement.setString(2, course.getCourseName());
        statement.setInt(3, course.getCredits());
        statement.setString(4, course.getDepartment());
    }

    private static Course mapRow(ResultSet rs) throws SQLException {
        Course course = new Course();
        course.setId(rs.getInt("id"));
        course.setCourseCode(rs.getString("course_code"));
        course.setCourseName(rs.getString("course_name"));
        course.setCredits(rs.getInt("credits"));
        course.setDepartment(rs.getString("department"));
        course.setCreatedAt(rs.getObject("created_at", LocalDateTime.class));
        course.setUpdatedAt(rs.getObject("updated_at", LocalDateTime.class));
        return course;
    }
}
