package com.studentmanagement.service;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import com.studentmanagement.dao.CourseDAO;
import com.studentmanagement.dao.EnrollmentDAO;
import com.studentmanagement.dao.StudentDAO;
import com.studentmanagement.model.Course;
import com.studentmanagement.model.Enrollment;
import com.studentmanagement.model.Student;
import com.studentmanagement.util.DatabaseUtils;

/**
 * Business logic for students: input validation, transactions spanning several
 * statements, CSV batch import and enrollment.
 *
 * <p>Validation failures are reported with {@link IllegalArgumentException} before any SQL is
 * executed; database failures are reported with {@link SQLException}.</p>
 */
public class StudentService {

    static final int NAME_MAX_LENGTH = 50;
    static final int EMAIL_MAX_LENGTH = 100;
    static final int DEPARTMENT_MAX_LENGTH = 50;
    static final BigDecimal GPA_MIN = new BigDecimal("0.00");
    static final BigDecimal GPA_MAX = new BigDecimal("4.00");

    /** Expected first line of a CSV import file. */
    public static final String CSV_HEADER = "first_name,last_name,email,date_of_birth,department,gpa,enrollment_date";

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern NAME_PATTERN = Pattern.compile("^\\p{L}[\\p{L} .'-]*$");
    private static final int CSV_COLUMNS = 7;

    private final DataSource dataSource;
    private final StudentDAO studentDAO;
    private final CourseDAO courseDAO;
    private final EnrollmentDAO enrollmentDAO;

    public StudentService(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.studentDAO = new StudentDAO(dataSource);
        this.courseDAO = new CourseDAO(dataSource);
        this.enrollmentDAO = new EnrollmentDAO(dataSource);
    }

    // ================================================================ CRUD

    /**
     * Validates and inserts a student.
     *
     * @return the stored student, re-read from the database (with id and timestamps)
     */
    public Student addStudent(Student student) throws SQLException {
        validateStudent(student);
        int id = studentDAO.addStudent(student);
        return readBack(id);
    }

    /**
     * Inserts a student AND enrolls them in the given courses as ONE transaction.
     * If any course code is unknown or any insert fails, the whole transaction is rolled
     * back and the student is not stored either.
     *
     * @param courseCodes course codes such as "CS101" (duplicates are ignored)
     * @return the stored student
     */
    public Student registerStudentWithCourses(Student student, List<String> courseCodes) throws SQLException {
        validateStudent(student);
        Set<String> codes = normalizeCourseCodes(courseCodes);
        if (codes.isEmpty()) {
            throw new IllegalArgumentException("At least one course code is required");
        }
        int id = DatabaseUtils.executeInTransaction(dataSource, connection -> {
            // 1. insert the student (generated id needed for the enrollments)
            int studentId = studentDAO.addStudent(connection, student);
            // 2. resolve every course code inside the same transaction
            List<Enrollment> enrollments = new ArrayList<>();
            for (String code : codes) {
                Course course = courseDAO.getCourseByCode(connection, code)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown course code: " + code));
                enrollments.add(new Enrollment(studentId, course.getId(), student.getEnrollmentDate(), null));
            }
            // 3. insert all enrollments as one JDBC batch
            enrollmentDAO.addEnrollmentsBatch(connection, enrollments);
            return studentId;
        });
        return readBack(id);
    }

    public Optional<Student> getStudentById(int id) throws SQLException {
        return studentDAO.getStudentById(id);
    }

    public List<Student> getAllStudents() throws SQLException {
        return studentDAO.getAllStudents();
    }

    /**
     * Searches by any combination of keyword (name / e-mail), department and minimum GPA.
     * At least one criterion must be given.
     */
    public List<Student> searchStudents(String keyword, String department, BigDecimal minGpa) throws SQLException {
        boolean noKeyword = keyword == null || keyword.trim().isEmpty();
        boolean noDepartment = department == null || department.trim().isEmpty();
        if (noKeyword && noDepartment && minGpa == null) {
            throw new IllegalArgumentException("Enter at least one search criterion");
        }
        if (minGpa != null) {
            requireValid(checkGpa(minGpa));
        }
        return studentDAO.searchStudents(keyword, department, minGpa);
    }

    /**
     * Validates and updates an existing student.
     *
     * @return true if the student exists
     */
    public boolean updateStudent(Student student) throws SQLException {
        if (student.getId() <= 0) {
            throw new IllegalArgumentException("A valid student id is required for an update");
        }
        validateStudent(student);
        return studentDAO.updateStudent(student);
    }

    /**
     * Deletes a student; the database removes their enrollments (ON DELETE CASCADE).
     *
     * @return true if the student existed
     */
    public boolean deleteStudent(int id) throws SQLException {
        return studentDAO.deleteStudent(id);
    }

    /** Students of one department via the stored procedure GetStudentsByDepartment. */
    public List<Student> getStudentsByDepartment(String department) throws SQLException {
        if (department == null || department.trim().isEmpty()) {
            throw new IllegalArgumentException("Department is required");
        }
        return studentDAO.getStudentsByDepartment(department.trim());
    }

    public List<String> getAllDepartments() throws SQLException {
        return studentDAO.getAllDepartments();
    }

    public List<Course> getAllCourses() throws SQLException {
        return courseDAO.getAllCourses();
    }

    public List<Enrollment> getEnrollmentsForStudent(int studentId) throws SQLException {
        return enrollmentDAO.getEnrollmentsByStudent(studentId);
    }

    // ============================================================= batches

    /**
     * Imports students from a CSV file (header {@value #CSV_HEADER}).
     * Every row is validated first; if any row is invalid nothing is written. Valid files are
     * inserted with one JDBC batch in one transaction (all rows or none).
     *
     * @return number of students imported
     */
    public int importStudentsFromCsv(Path csvFile) throws IOException, SQLException {
        return studentDAO.addStudentsBatch(parseStudentsCsv(csvFile));
    }

    /**
     * Enrolls several students in one course with a single JDBC batch in one transaction:
     * if any student id does not exist (foreign key) or is already enrolled (primary key),
     * no enrollment is stored.
     *
     * @return number of enrollments created
     */
    public int enrollStudentsInCourse(String courseCode, List<Integer> studentIds) throws SQLException {
        Set<String> codes = normalizeCourseCodes(List.of(courseCode == null ? "" : courseCode));
        if (codes.isEmpty()) {
            throw new IllegalArgumentException("Course code is required");
        }
        Set<Integer> ids = new LinkedHashSet<>(studentIds);
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("At least one student id is required");
        }
        String code = codes.iterator().next();
        LocalDate today = LocalDate.now();
        return DatabaseUtils.executeInTransaction(dataSource, connection -> {
            Course course = courseDAO.getCourseByCode(connection, code)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown course code: " + code));
            List<Enrollment> enrollments = new ArrayList<>();
            for (int studentId : ids) {
                enrollments.add(new Enrollment(studentId, course.getId(), today, null));
            }
            return enrollmentDAO.addEnrollmentsBatch(connection, enrollments);
        });
    }

    private List<Student> parseStudentsCsv(Path csvFile) throws IOException {
        if (!Files.isRegularFile(csvFile)) {
            throw new FileNotFoundException("CSV file not found: " + csvFile);
        }
        List<String> lines = Files.readAllLines(csvFile, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !CSV_HEADER.equalsIgnoreCase(lines.get(0).replace("﻿", "").trim())) {
            throw new IllegalArgumentException("The first line of " + csvFile + " must be the header: " + CSV_HEADER);
        }
        List<Student> students = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Map<String, Integer> emailToLine = new HashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            int lineNumber = i + 1;
            if (line.trim().isEmpty()) {
                continue;
            }
            String[] fields = line.split(",", -1);
            if (fields.length != CSV_COLUMNS) {
                errors.add("line " + lineNumber + ": expected " + CSV_COLUMNS + " values but found " + fields.length);
                continue;
            }
            Student student;
            try {
                student = new Student(fields[0], fields[1], fields[2], LocalDate.parse(fields[3].trim()), fields[4],
                        fields[5].trim().isEmpty() ? null : new BigDecimal(fields[5].trim()),
                        fields[6].trim().isEmpty() ? null : LocalDate.parse(fields[6].trim()));
            } catch (DateTimeParseException e) {
                errors.add("line " + lineNumber + ": dates must use the format yyyy-MM-dd");
                continue;
            } catch (NumberFormatException e) {
                errors.add("line " + lineNumber + ": GPA must be a number");
                continue;
            }
            List<String> problems = normalizeAndCollectErrors(student);
            Integer firstLine = emailToLine.putIfAbsent(String.valueOf(student.getEmail()), lineNumber);
            if (firstLine != null) {
                problems.add("e-mail " + student.getEmail() + " already used on line " + firstLine);
            }
            if (problems.isEmpty()) {
                students.add(student);
            } else {
                errors.add("line " + lineNumber + ": " + String.join("; ", problems));
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("The CSV file contains invalid rows, nothing was imported:\n  - "
                    + String.join("\n  - ", errors));
        }
        if (students.isEmpty()) {
            throw new IllegalArgumentException("The CSV file contains no data rows");
        }
        return students;
    }

    // ========================================================== validation

    /**
     * Normalises (trims text, lower-cases the e-mail, defaults the enrollment date to today)
     * and validates a student.
     *
     * @throws IllegalArgumentException listing every problem found
     */
    public static void validateStudent(Student student) {
        List<String> errors = normalizeAndCollectErrors(student);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
    }

    private static List<String> normalizeAndCollectErrors(Student student) {
        student.setFirstName(trim(student.getFirstName()));
        student.setLastName(trim(student.getLastName()));
        student.setEmail(student.getEmail() == null ? null : student.getEmail().trim().toLowerCase(Locale.ROOT));
        student.setDepartment(trim(student.getDepartment()));
        if (student.getEnrollmentDate() == null) {
            student.setEnrollmentDate(LocalDate.now());
        }
        List<String> errors = new ArrayList<>();
        addIfInvalid(errors, checkName("First name", student.getFirstName()));
        addIfInvalid(errors, checkName("Last name", student.getLastName()));
        addIfInvalid(errors, checkEmail(student.getEmail()));
        addIfInvalid(errors, checkDateOfBirth(student.getDateOfBirth()));
        addIfInvalid(errors, checkDepartment(student.getDepartment()));
        addIfInvalid(errors, checkGpa(student.getGpa()));
        addIfInvalid(errors, checkEnrollmentDate(student.getEnrollmentDate(), student.getDateOfBirth()));
        return errors;
    }

    /** @return an error message, or null if the name is valid */
    public static String checkName(String label, String value) {
        if (value == null || value.trim().isEmpty()) {
            return label + " is required";
        }
        if (value.trim().length() > NAME_MAX_LENGTH) {
            return label + " must be at most " + NAME_MAX_LENGTH + " characters";
        }
        if (!NAME_PATTERN.matcher(value.trim()).matches()) {
            return label + " may only contain letters, spaces, apostrophes, dots and hyphens";
        }
        return null;
    }

    /** @return an error message, or null if the e-mail address is valid */
    public static String checkEmail(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "Email is required";
        }
        if (value.trim().length() > EMAIL_MAX_LENGTH) {
            return "Email must be at most " + EMAIL_MAX_LENGTH + " characters";
        }
        if (!EMAIL_PATTERN.matcher(value.trim()).matches()) {
            return "Email '" + value.trim() + "' is not a valid e-mail address";
        }
        return null;
    }

    /** @return an error message, or null if the date of birth is valid */
    public static String checkDateOfBirth(LocalDate dateOfBirth) {
        if (dateOfBirth == null) {
            return "Date of birth is required";
        }
        if (!dateOfBirth.isBefore(LocalDate.now())) {
            return "Date of birth must be in the past";
        }
        return null;
    }

    /** @return an error message, or null if the department is valid */
    public static String checkDepartment(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "Department is required";
        }
        if (value.trim().length() > DEPARTMENT_MAX_LENGTH) {
            return "Department must be at most " + DEPARTMENT_MAX_LENGTH + " characters";
        }
        return null;
    }

    /** @return an error message, or null if the GPA is valid (null = no GPA yet, allowed) */
    public static String checkGpa(BigDecimal gpa) {
        if (gpa == null) {
            return null;
        }
        if (gpa.compareTo(GPA_MIN) < 0 || gpa.compareTo(GPA_MAX) > 0) {
            return "GPA must be between 0.00 and 4.00";
        }
        if (gpa.stripTrailingZeros().scale() > 2) {
            return "GPA may have at most 2 decimal places";
        }
        return null;
    }

    /** @return an error message, or null if the enrollment date is valid */
    public static String checkEnrollmentDate(LocalDate enrollmentDate, LocalDate dateOfBirth) {
        if (enrollmentDate == null) {
            return "Enrollment date is required";
        }
        if (dateOfBirth != null && !enrollmentDate.isAfter(dateOfBirth)) {
            return "Enrollment date must be after the date of birth";
        }
        return null;
    }

    // ============================================================= helpers

    private Student readBack(int id) throws SQLException {
        return studentDAO.getStudentById(id)
                .orElseThrow(() -> new SQLException("Student " + id + " was saved but could not be read back"));
    }

    private static Set<String> normalizeCourseCodes(List<String> courseCodes) {
        Set<String> codes = new LinkedHashSet<>();
        if (courseCodes != null) {
            for (String code : courseCodes) {
                if (code != null && !code.trim().isEmpty()) {
                    codes.add(code.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        return codes;
    }

    private static void requireValid(String error) {
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
    }

    private static void addIfInvalid(List<String> errors, String error) {
        if (error != null) {
            errors.add(error);
        }
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
