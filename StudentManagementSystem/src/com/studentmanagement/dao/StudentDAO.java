package com.studentmanagement.dao;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

import com.studentmanagement.model.Student;
import com.studentmanagement.util.DatabaseUtils;
import com.studentmanagement.util.QueryBuilder;

/**
 * Data access for the {@code students} table.
 *
 * <p>Every statement is a {@link PreparedStatement} (or {@link CallableStatement}) with
 * {@code ?} placeholders; user input is never concatenated into SQL. All JDBC resources are
 * closed with try-with-resources, which also returns the connection to the pool.</p>
 */
public class StudentDAO {

    private static final String[] COLUMNS = {"id", "first_name", "last_name", "email", "date_of_birth",
            "department", "gpa", "enrollment_date", "created_at", "updated_at"};
    private static final String COLUMN_LIST = String.join(", ", COLUMNS);

    private static final String INSERT_SQL = "INSERT INTO students "
            + "(first_name, last_name, email, date_of_birth, department, gpa, enrollment_date) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?)";
    private static final String SELECT_BY_ID_SQL = "SELECT " + COLUMN_LIST + " FROM students WHERE id = ?";
    private static final String SELECT_ALL_SQL = "SELECT " + COLUMN_LIST + " FROM students ORDER BY id";
    private static final String UPDATE_SQL = "UPDATE students SET first_name = ?, last_name = ?, email = ?, "
            + "date_of_birth = ?, department = ?, gpa = ?, enrollment_date = ? WHERE id = ?";
    private static final String DELETE_SQL = "DELETE FROM students WHERE id = ?";
    private static final String SELECT_DEPARTMENTS_SQL =
            "SELECT DISTINCT department FROM students ORDER BY department";
    private static final String CALL_GET_STUDENTS_BY_DEPARTMENT = "{CALL GetStudentsByDepartment(?)}";

    /** Rows sent to the server per executeBatch() call. */
    static final int BATCH_SIZE = 500;

    private final DataSource dataSource;

    public StudentDAO(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    // ------------------------------------------------------------- create

    /**
     * Inserts a student and stores the generated id in the object.
     *
     * @return the generated id
     */
    public int addStudent(Student student) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return addStudent(connection, student);
        }
    }

    /**
     * Inserts a student on the given connection, so the insert can take part in a
     * caller-managed transaction (the connection is not closed or committed here).
     */
    public int addStudent(Connection connection, Student student) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {
            bindStudent(statement, student);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("The insert succeeded but no generated id was returned");
                }
                student.setId(keys.getInt(1));
            }
        }
        return student.getId();
    }

    /**
     * Inserts all students using JDBC batching ({@code addBatch()} / {@code executeBatch()})
     * inside ONE transaction: either every row is stored or, if any row fails, none is.
     * Large lists are sent in chunks of {@value #BATCH_SIZE}. Generated ids are written back.
     *
     * @return number of students inserted
     */
    public int addStudentsBatch(List<Student> students) throws SQLException {
        if (students.isEmpty()) {
            return 0;
        }
        return DatabaseUtils.executeInTransaction(dataSource, connection -> {
            int inserted = 0;
            try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {
                int queued = 0;
                for (Student student : students) {
                    bindStudent(statement, student);
                    statement.addBatch();
                    if (++queued == BATCH_SIZE) {
                        inserted += executeBatchAndAssignIds(statement, students, inserted);
                        queued = 0;
                    }
                }
                if (queued > 0) {
                    inserted += executeBatchAndAssignIds(statement, students, inserted);
                }
            }
            return inserted;
        });
    }

    private static int executeBatchAndAssignIds(PreparedStatement statement, List<Student> students, int offset)
            throws SQLException {
        int[] updateCounts = statement.executeBatch();
        for (int count : updateCounts) {
            // With rewriteBatchedStatements=true the driver reports SUCCESS_NO_INFO (-2).
            if (count == Statement.EXECUTE_FAILED) {
                throw new SQLException("A statement in the batch failed");
            }
        }
        try (ResultSet keys = statement.getGeneratedKeys()) {
            int index = offset;
            while (keys.next() && index < students.size()) {
                students.get(index++).setId(keys.getInt(1));
            }
        }
        return updateCounts.length;
    }

    // --------------------------------------------------------------- read

    /** Finds a student by primary key. */
    public Optional<Student> getStudentById(int id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_BY_ID_SQL)) {
            statement.setInt(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    /** Returns all students ordered by id. */
    public List<Student> getAllStudents() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_ALL_SQL);
             ResultSet rs = statement.executeQuery()) {
            return mapRows(rs);
        }
    }

    /**
     * Searches students. Every criterion is optional (null or blank = not used) and the
     * given criteria are combined with AND:
     *
     * @param keyword    part of the first name, last name, full name or e-mail (case-insensitive)
     * @param department exact department name (case-insensitive)
     * @param minGpa     minimum GPA (students without a GPA are excluded when given)
     */
    public List<Student> searchStudents(String keyword, String department, BigDecimal minGpa) throws SQLException {
        String trimmedKeyword = keyword == null ? "" : keyword.trim();
        String trimmedDepartment = department == null ? "" : department.trim();
        String pattern = QueryBuilder.containsPattern(trimmedKeyword);

        QueryBuilder query = QueryBuilder.select("students", COLUMNS)
                .whereIf(!trimmedKeyword.isEmpty(),
                        "first_name LIKE ? OR last_name LIKE ? OR email LIKE ? OR CONCAT(first_name, ' ', last_name) LIKE ?",
                        pattern, pattern, pattern, pattern)
                .whereIf(!trimmedDepartment.isEmpty(), "department = ?", trimmedDepartment)
                .whereIf(minGpa != null, "gpa >= ?", minGpa)
                .orderBy("last_name", true)
                .orderBy("first_name", true);

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(query.build())) {
            DatabaseUtils.setParameters(statement, query.getParameters());
            try (ResultSet rs = statement.executeQuery()) {
                return mapRows(rs);
            }
        }
    }

    /**
     * Calls the stored procedure {@code GetStudentsByDepartment} through a CallableStatement.
     */
    public List<Student> getStudentsByDepartment(String department) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             CallableStatement statement = connection.prepareCall(CALL_GET_STUDENTS_BY_DEPARTMENT)) {
            statement.setString(1, department);
            try (ResultSet rs = statement.executeQuery()) {
                return mapRows(rs);
            }
        }
    }

    /** Distinct department names, alphabetically. */
    public List<String> getAllDepartments() throws SQLException {
        List<String> departments = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_DEPARTMENTS_SQL);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                departments.add(rs.getString(1));
            }
        }
        return departments;
    }

    // ------------------------------------------------------ update / delete

    /**
     * Updates all editable columns of the student with {@code student.getId()}.
     * ({@code updated_at} is maintained by MySQL.)
     *
     * @return true if the student exists
     */
    public boolean updateStudent(Student student) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(UPDATE_SQL)) {
            bindStudent(statement, student);
            statement.setInt(8, student.getId());
            return statement.executeUpdate() == 1;
        }
    }

    /**
     * Deletes a student. Their enrollments are removed by the database
     * ({@code ON DELETE CASCADE} on {@code student_courses.student_id}).
     *
     * @return true if a student was deleted
     */
    public boolean deleteStudent(int id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(DELETE_SQL)) {
            statement.setInt(1, id);
            return statement.executeUpdate() == 1;
        }
    }

    // ------------------------------------------------------------ mapping

    /** Binds the 7 editable columns in the order used by INSERT_SQL and UPDATE_SQL. */
    private static void bindStudent(PreparedStatement statement, Student student) throws SQLException {
        statement.setString(1, student.getFirstName());
        statement.setString(2, student.getLastName());
        statement.setString(3, student.getEmail());
        statement.setObject(4, student.getDateOfBirth());          // LocalDate -> DATE
        statement.setString(5, student.getDepartment());
        if (student.getGpa() == null) {
            statement.setNull(6, Types.DECIMAL);
        } else {
            statement.setBigDecimal(6, student.getGpa());
        }
        statement.setObject(7, student.getEnrollmentDate());       // LocalDate -> DATE
    }

    private static List<Student> mapRows(ResultSet rs) throws SQLException {
        List<Student> students = new ArrayList<>();
        while (rs.next()) {
            students.add(mapRow(rs));
        }
        return students;
    }

    private static Student mapRow(ResultSet rs) throws SQLException {
        Student student = new Student();
        student.setId(rs.getInt("id"));
        student.setFirstName(rs.getString("first_name"));
        student.setLastName(rs.getString("last_name"));
        student.setEmail(rs.getString("email"));
        student.setDateOfBirth(rs.getObject("date_of_birth", LocalDate.class));
        student.setDepartment(rs.getString("department"));
        student.setGpa(rs.getBigDecimal("gpa"));
        student.setEnrollmentDate(rs.getObject("enrollment_date", LocalDate.class));
        student.setCreatedAt(rs.getObject("created_at", LocalDateTime.class));
        student.setUpdatedAt(rs.getObject("updated_at", LocalDateTime.class));
        return student;
    }
}
