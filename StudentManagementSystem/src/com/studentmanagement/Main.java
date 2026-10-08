package com.studentmanagement;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.function.Function;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.sql.DataSource;

import com.studentmanagement.config.DatabaseConfig;
import com.studentmanagement.config.DatabaseInitializer;
import com.studentmanagement.model.Course;
import com.studentmanagement.model.Enrollment;
import com.studentmanagement.model.Student;
import com.studentmanagement.service.ReportService;
import com.studentmanagement.service.StudentService;
import com.studentmanagement.util.DatabaseUtils;

/**
 * Console application - Student Management System (Java JDBC + MySQL).
 *
 * <pre>
 * Interactive menu:   run.sh  /  run.bat
 * Backup:             run.sh --backup
 * Restore:            run.sh --restore backups/&lt;file&gt;.sql
 * </pre>
 * Technical error details (stack traces) are written to {@value #LOG_FILE}.
 */
public class Main {

    static final String LOG_FILE = "logs/application.log";
    /** Set -Dsms.echoInput=true to echo typed input (makes logs of piped sessions readable). */
    static final String ECHO_INPUT_PROPERTY = "sms.echoInput";
    static final String DEFAULT_CSV_FILE = "database/sample_students.csv";

    private static final Logger LOGGER = Logger.getLogger(Main.class.getName());
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String RULE = "-----------------------------------------------------------------"
            + "-----------------------------------------";

    private final Scanner scanner;
    private final PrintStream out;
    private final boolean echoInput;
    private final StudentService studentService;
    private final ReportService reportService;
    private final DatabaseInitializer initializer;

    Main(InputStream in, PrintStream out, StudentService studentService, ReportService reportService,
         DatabaseInitializer initializer) {
        this.scanner = new Scanner(in, StandardCharsets.UTF_8.name());
        this.out = out;
        this.echoInput = Boolean.getBoolean(ECHO_INPUT_PROPERTY);
        this.studentService = studentService;
        this.reportService = reportService;
        this.initializer = initializer;
    }

    public static void main(String[] args) {
        configureLogging();
        int exitCode = 0;
        try {
            if (args.length == 0) {
                DataSource dataSource = DatabaseConfig.getDataSource();
                new Main(System.in, System.out, new StudentService(dataSource), new ReportService(dataSource),
                        new DatabaseInitializer()).run();
            } else {
                exitCode = runCommand(args, System.in, System.out);
            }
        } catch (IllegalStateException e) {
            System.out.println("Configuration error: " + e.getMessage());
            LOGGER.log(Level.SEVERE, "Configuration error", e);
            exitCode = 1;
        } finally {
            DatabaseConfig.closePool();
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    // =====================================================================
    // Interactive menu
    // =====================================================================

    /** Shows the menu until the user chooses Exit (or the input ends). */
    void run() {
        printBanner();
        showConnectionStatus();
        boolean running = true;
        while (running) {
            printMenu();
            try {
                int choice = readInt("Enter your choice (1-10): ", 1, 10);
                switch (choice) {
                    case 1:
                        initializeDatabase();
                        break;
                    case 2:
                        addNewStudent();
                        break;
                    case 3:
                        viewAllStudents();
                        break;
                    case 4:
                        searchStudents();
                        break;
                    case 5:
                        updateStudent();
                        break;
                    case 6:
                        deleteStudent();
                        break;
                    case 7:
                        batchOperations();
                        break;
                    case 8:
                        callStoredProcedure();
                        break;
                    case 9:
                        showDatabaseStatistics();
                        break;
                    case 10:
                        out.println("Goodbye!");
                        running = false;
                        break;
                    default:
                        break;          // not reachable: readInt only accepts 1-10
                }
            } catch (InputClosedException e) {
                out.println();
                out.println("Input closed - exiting.");
                running = false;
            } catch (IllegalArgumentException e) {
                out.println("Validation error: " + e.getMessage());
            } catch (SQLException e) {
                out.println("ERROR: " + DatabaseUtils.describe(e));
                LOGGER.log(Level.WARNING, "Database operation failed", e);
            } catch (IOException e) {
                out.println("ERROR: " + e.getMessage());
                LOGGER.log(Level.WARNING, "File operation failed", e);
            } catch (RuntimeException e) {
                out.println("Unexpected error: " + e + " (details in " + LOG_FILE + ")");
                LOGGER.log(Level.SEVERE, "Unexpected error", e);
            }
        }
    }

    private void printBanner() {
        out.println("==========================================================");
        out.println("     STUDENT MANAGEMENT SYSTEM  (Java JDBC + MySQL)");
        out.println("==========================================================");
    }

    private void showConnectionStatus() {
        try {
            out.println("Connected: " + reportService.getServerInfo());
            Map<String, Integer> pool = reportService.getConnectionPoolStatus();
            out.printf("Connection pool: DBCP2 (min idle %d, max idle %d, max total %d)%n",
                    pool.get("minIdle"), pool.get("maxIdle"), pool.get("maxTotal"));
            if (!initializer.isSchemaInitialized()) {
                out.println("Database '" + DatabaseConfig.getDatabaseName()
                        + "' is not initialized yet - choose option 1 first.");
            }
        } catch (SQLException e) {
            out.println("WARNING: " + DatabaseUtils.describe(e));
            LOGGER.log(Level.WARNING, "Startup connection check failed", e);
        }
    }

    private void printMenu() {
        out.println();
        out.println("========================= MAIN MENU =========================");
        out.println(" 1. Initialize Database");
        out.println(" 2. Add New Student");
        out.println(" 3. View All Students");
        out.println(" 4. Search Students");
        out.println(" 5. Update Student");
        out.println(" 6. Delete Student");
        out.println(" 7. Batch Operations");
        out.println(" 8. Call Stored Procedure");
        out.println(" 9. Database Statistics");
        out.println("10. Exit");
    }

    // ---------------------------------------------------- 1. initialize

    private void initializeDatabase() throws SQLException, IOException {
        title("Initialize Database");
        out.println("Creates the database (if missing), tables, constraints, indexes and stored procedures.");
        out.println("Existing data is kept.");
        boolean loadSampleData = readYesNo("Load sample data if the database is empty? (y/n): ");
        for (String step : initializer.initializeDatabase(loadSampleData)) {
            out.println("  [OK] " + step);
        }
    }

    // ---------------------------------------------------- 2. add

    private void addNewStudent() throws SQLException {
        title("Add New Student");
        Student student = new Student();
        student.setFirstName(readValidated("First name: ", value -> StudentService.checkName("First name", value)));
        student.setLastName(readValidated("Last name: ", value -> StudentService.checkName("Last name", value)));
        student.setEmail(readValidated("Email: ", StudentService::checkEmail));
        student.setDateOfBirth(readDate("Date of birth (yyyy-MM-dd): ", null, StudentService::checkDateOfBirth));
        student.setDepartment(readValidated("Department: ", StudentService::checkDepartment));
        student.setGpa(readGpa("GPA 0.00-4.00 (Enter = no GPA yet): ", null));
        student.setEnrollmentDate(readDate("Enrollment date (yyyy-MM-dd, Enter = today): ", LocalDate.now(),
                date -> StudentService.checkEnrollmentDate(date, student.getDateOfBirth())));

        printCourseCatalogue();
        List<String> courseCodes = splitList(readLine("Enroll in courses now? Course codes separated by commas "
                + "(Enter = skip): "));
        Student saved;
        if (courseCodes.isEmpty()) {
            saved = studentService.addStudent(student);
            out.println("[OK] Student added with ID " + saved.getId());
        } else {
            try {
                saved = studentService.registerStudentWithCourses(student, courseCodes);
            } catch (SQLException | IllegalArgumentException e) {
                out.println("Transaction rolled back - the student was NOT saved.");
                throw e;
            }
            out.println("[OK] Student added with ID " + saved.getId() + " and enrolled in "
                    + studentService.getEnrollmentsForStudent(saved.getId()).size()
                    + " course(s) - committed as one transaction");
        }
        printStudentDetails(saved);
    }

    // ---------------------------------------------------- 3. view all

    private void viewAllStudents() throws SQLException {
        title("All Students");
        List<Student> students = studentService.getAllStudents();
        printStudentTable(students);
        out.println(students.size() + " student(s)");
    }

    // ---------------------------------------------------- 4. search

    private void searchStudents() throws SQLException {
        title("Search Students");
        out.println("1. By student ID");
        out.println("2. By name/email, department and/or minimum GPA");
        if (readInt("Choose (1-2): ", 1, 2) == 1) {
            int id = readInt("Student ID: ", 1, Integer.MAX_VALUE);
            Optional<Student> student = studentService.getStudentById(id);
            if (student.isPresent()) {
                printStudentDetails(student.get());
            } else {
                out.println("No student found with ID " + id);
            }
            return;
        }
        out.println("Leave a field empty to ignore it.");
        String keyword = readLine("Name or email contains: ");
        String department = readLine("Department: ");
        BigDecimal minGpa = readGpa("Minimum GPA: ", null);
        List<Student> students = studentService.searchStudents(keyword, department, minGpa);
        printStudentTable(students);
        out.println(students.size() + " student(s) found");
    }

    // ---------------------------------------------------- 5. update

    private void updateStudent() throws SQLException {
        title("Update Student");
        int id = readInt("Student ID to update: ", 1, Integer.MAX_VALUE);
        Optional<Student> found = studentService.getStudentById(id);
        if (!found.isPresent()) {
            out.println("No student found with ID " + id);
            return;
        }
        Student student = found.get();
        printStudentDetails(student);
        out.println("Enter new values - press Enter to keep the current value shown in [brackets].");
        student.setFirstName(readValidatedOrKeep("First name", student.getFirstName(),
                value -> StudentService.checkName("First name", value)));
        student.setLastName(readValidatedOrKeep("Last name", student.getLastName(),
                value -> StudentService.checkName("Last name", value)));
        student.setEmail(readValidatedOrKeep("Email", student.getEmail(), StudentService::checkEmail));
        student.setDateOfBirth(readDate("Date of birth [" + student.getDateOfBirth() + "]: ",
                student.getDateOfBirth(), StudentService::checkDateOfBirth));
        student.setDepartment(readValidatedOrKeep("Department", student.getDepartment(),
                StudentService::checkDepartment));
        student.setGpa(readGpa("GPA [" + (student.getGpa() == null ? "none" : student.getGpa()) + "]: ",
                student.getGpa()));
        student.setEnrollmentDate(readDate("Enrollment date [" + student.getEnrollmentDate() + "]: ",
                student.getEnrollmentDate(),
                date -> StudentService.checkEnrollmentDate(date, student.getDateOfBirth())));

        if (studentService.updateStudent(student)) {
            out.println("[OK] Student " + id + " updated");
            studentService.getStudentById(id).ifPresent(this::printStudentDetails);
        } else {
            out.println("Student " + id + " no longer exists - nothing updated");
        }
    }

    // ---------------------------------------------------- 6. delete

    private void deleteStudent() throws SQLException {
        title("Delete Student");
        int id = readInt("Student ID to delete: ", 1, Integer.MAX_VALUE);
        Optional<Student> found = studentService.getStudentById(id);
        if (!found.isPresent()) {
            out.println("No student found with ID " + id);
            return;
        }
        printStudentDetails(found.get());
        int enrollments = studentService.getEnrollmentsForStudent(id).size();
        String question = "Delete this student" + (enrollments > 0 ? " and their " + enrollments + " enrollment(s)" : "")
                + "? (y/n): ";
        if (!readYesNo(question)) {
            out.println("Delete cancelled - nothing changed.");
            return;
        }
        if (studentService.deleteStudent(id)) {
            out.println("[OK] Student " + id + " deleted"
                    + (enrollments > 0 ? " (" + enrollments + " enrollment(s) removed by ON DELETE CASCADE)" : ""));
        } else {
            out.println("Student " + id + " was already deleted");
        }
    }

    // ---------------------------------------------------- 7. batch

    private void batchOperations() throws SQLException, IOException {
        title("Batch Operations");
        out.println("1. Import students from a CSV file (batch insert, one transaction)");
        out.println("2. Enroll several students in one course (batch insert, one transaction)");
        out.println("3. Back to main menu");
        int choice = readInt("Choose (1-3): ", 1, 3);
        if (choice == 1) {
            out.println("CSV header must be: " + StudentService.CSV_HEADER);
            String input = readLine("CSV file [" + DEFAULT_CSV_FILE + "]: ");
            Path file = Paths.get(input.isEmpty() ? DEFAULT_CSV_FILE : input);
            long start = System.nanoTime();
            int imported;
            try {
                imported = studentService.importStudentsFromCsv(file);
            } catch (SQLException e) {
                out.println("Batch failed - transaction rolled back, no students were imported.");
                throw e;
            }
            out.printf("[OK] Batch committed: %d student(s) inserted with addBatch()/executeBatch() in %d ms%n",
                    imported, (System.nanoTime() - start) / 1_000_000);
        } else if (choice == 2) {
            printCourseCatalogue();
            String courseCode = readValidated("Course code: ",
                    value -> value.trim().isEmpty() ? "Course code is required" : null);
            List<Integer> studentIds = readIdList("Student IDs separated by commas: ");
            int enrolled;
            try {
                enrolled = studentService.enrollStudentsInCourse(courseCode, studentIds);
            } catch (SQLException | IllegalArgumentException e) {
                out.println("Batch failed - transaction rolled back, no enrollments were saved.");
                throw e;
            }
            out.println("[OK] Batch committed: " + enrolled + " enrollment(s) created for "
                    + courseCode.toUpperCase(Locale.ROOT));
        }
    }

    // ---------------------------------------------------- 8. procedure

    private void callStoredProcedure() throws SQLException {
        title("Call Stored Procedure: GetStudentsByDepartment");
        List<String> departments = studentService.getAllDepartments();
        if (!departments.isEmpty()) {
            out.println("Departments: " + String.join(", ", departments));
        }
        String department = readValidated("Department: ", StudentService::checkDepartment);
        out.println("Executing {CALL GetStudentsByDepartment(?)} with ? = '" + department + "'");
        List<Student> students = studentService.getStudentsByDepartment(department);
        printStudentTable(students);
        out.println(students.size() + " student(s) returned by the stored procedure");
    }

    // ---------------------------------------------------- 9. statistics

    private void showDatabaseStatistics() throws SQLException {
        title("Database Statistics");
        out.print(reportService.generateStatisticsReport());
    }

    // =====================================================================
    // Command-line options: --backup / --restore <file>
    // =====================================================================

    static int runCommand(String[] args, InputStream in, PrintStream out) {
        try {
            switch (args[0]) {
                case "--backup": {
                    out.println("Creating backup of database '" + DatabaseConfig.getDatabaseName() + "' with mysqldump ...");
                    Path file = DatabaseUtils.backupDatabase(DatabaseConfig.getBackupDirectory());
                    out.printf("[OK] Backup created: %s (%,d bytes) - tables, data and stored procedures%n",
                            file, Files.size(file));
                    return 0;
                }
                case "--restore": {
                    if (args.length < 2) {
                        out.println("Usage: --restore <backup-file.sql>");
                        printAvailableBackups(out);
                        return 2;
                    }
                    Path file = Paths.get(args[1]);
                    out.println("WARNING: this replaces all tables, data and stored procedures of database '"
                            + DatabaseConfig.getDatabaseName() + "' with the contents of " + file);
                    out.print("Type YES to continue: ");
                    out.flush();
                    Scanner confirmation = new Scanner(in, StandardCharsets.UTF_8.name());
                    String answer = confirmation.hasNextLine() ? confirmation.nextLine().trim() : "";
                    if (Boolean.getBoolean(ECHO_INPUT_PROPERTY)) {
                        out.println(answer);
                    }
                    if (!"YES".equals(answer)) {
                        out.println("Restore cancelled - nothing changed.");
                        return 1;
                    }
                    DatabaseUtils.restoreDatabase(file);
                    Map<String, Number> totals = new ReportService(DatabaseConfig.getDataSource()).getDatabaseTotals();
                    out.printf("[OK] Database restored from %s - now %d students, %d courses, %d enrollments%n",
                            file, totals.get("students").intValue(), totals.get("courses").intValue(),
                            totals.get("enrollments").intValue());
                    return 0;
                }
                case "--help":
                case "-h":
                    printUsage(out);
                    return 0;
                default:
                    out.println("Unknown option: " + args[0]);
                    printUsage(out);
                    return 2;
            }
        } catch (IOException e) {
            out.println("ERROR: " + e.getMessage());
            LOGGER.log(Level.WARNING, "Backup/restore failed", e);
            return 1;
        } catch (SQLException e) {
            out.println("ERROR: " + DatabaseUtils.describe(e));
            LOGGER.log(Level.WARNING, "Database error", e);
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.println("ERROR: interrupted");
            return 1;
        }
    }

    private static void printUsage(PrintStream out) {
        out.println("Usage:");
        out.println("  run                     start the interactive menu");
        out.println("  run --backup            back up the database to " + DatabaseConfig.getBackupDirectory());
        out.println("  run --restore <file>    restore the database from a backup file");
    }

    private static void printAvailableBackups(PrintStream out) throws IOException {
        Path directory = DatabaseConfig.getBackupDirectory();
        if (!Files.isDirectory(directory)) {
            out.println("No backups found in " + directory);
            return;
        }
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> backups = files.filter(p -> p.toString().endsWith(".sql")).sorted().collect(Collectors.toList());
            out.println(backups.isEmpty() ? "No backups found in " + directory : "Available backups:");
            backups.forEach(p -> out.println("  " + p));
        }
    }

    // =====================================================================
    // Output helpers
    // =====================================================================

    private void title(String text) {
        out.println();
        out.println("--- " + text + " ---");
    }

    private void printStudentTable(List<Student> students) {
        if (students.isEmpty()) {
            out.println("(no students)");
            return;
        }
        String format = "%-5s %-24s %-32s %-18s %-5s %-10s %-10s%n";
        out.printf(format, "ID", "Name", "Email", "Department", "GPA", "Born", "Enrolled");
        out.println(RULE);
        for (Student s : students) {
            out.printf(format, s.getId(), fit(s.getFullName(), 24), fit(s.getEmail(), 32), fit(s.getDepartment(), 18),
                    s.getGpa() == null ? "-" : s.getGpa(), s.getDateOfBirth(), s.getEnrollmentDate());
        }
    }

    private void printStudentDetails(Student student) {
        out.println("  ID              : " + student.getId());
        out.println("  Name            : " + student.getFullName());
        out.println("  Email           : " + student.getEmail());
        out.println("  Date of birth   : " + student.getDateOfBirth());
        out.println("  Department      : " + student.getDepartment());
        out.println("  GPA             : " + (student.getGpa() == null ? "-" : student.getGpa()));
        out.println("  Enrollment date : " + student.getEnrollmentDate());
        out.println("  Created         : " + formatTimestamp(student.getCreatedAt()));
        out.println("  Last updated    : " + formatTimestamp(student.getUpdatedAt()));
        try {
            List<Enrollment> enrollments = studentService.getEnrollmentsForStudent(student.getId());
            if (enrollments.isEmpty()) {
                out.println("  Courses         : none");
                return;
            }
            Map<Integer, Course> courses = new HashMap<>();
            for (Course course : studentService.getAllCourses()) {
                courses.put(course.getId(), course);
            }
            out.println("  Courses         :");
            for (Enrollment enrollment : enrollments) {
                Course course = courses.get(enrollment.getCourseId());
                out.printf("      %-7s %-34s enrolled %s  grade %s%n",
                        course == null ? "#" + enrollment.getCourseId() : course.getCourseCode(),
                        course == null ? "" : fit(course.getCourseName(), 34),
                        enrollment.getEnrollmentDate(),
                        enrollment.getGrade() == null ? "in progress" : enrollment.getGrade());
            }
        } catch (SQLException e) {
            out.println("  Courses         : could not be loaded - " + DatabaseUtils.describe(e));
            LOGGER.log(Level.WARNING, "Could not load enrollments", e);
        }
    }

    private void printCourseCatalogue() throws SQLException {
        List<Course> courses = studentService.getAllCourses();
        if (courses.isEmpty()) {
            out.println("(no courses in the catalogue yet)");
            return;
        }
        out.println("Available courses:");
        for (Course course : courses) {
            out.printf("  %-7s %-34s %d credits  (%s)%n", course.getCourseCode(), fit(course.getCourseName(), 34),
                    course.getCredits(), course.getDepartment());
        }
    }

    private static String formatTimestamp(LocalDateTime timestamp) {
        return timestamp == null ? "-" : timestamp.format(TIMESTAMP_FORMAT);
    }

    private static String fit(String value, int width) {
        if (value == null) {
            return "";
        }
        return value.length() <= width ? value : value.substring(0, width - 3) + "...";
    }

    // =====================================================================
    // Input helpers (every reader repeats the question until the input is valid)
    // =====================================================================

    private String readLine(String prompt) {
        out.print(prompt);
        out.flush();
        if (!scanner.hasNextLine()) {
            throw new InputClosedException();
        }
        String line = scanner.nextLine().trim();
        if (echoInput) {
            out.println(line);
        }
        return line;
    }

    private int readInt(String prompt, int min, int max) {
        while (true) {
            String input = readLine(prompt);
            try {
                int value = Integer.parseInt(input);
                if (value >= min && value <= max) {
                    return value;
                }
            } catch (NumberFormatException e) {
                // handled below: the question is repeated
            }
            out.println(max == Integer.MAX_VALUE
                    ? "  Please enter a whole number of at least " + min + "."
                    : "  Please enter a number between " + min + " and " + max + ".");
        }
    }

    private boolean readYesNo(String prompt) {
        while (true) {
            String input = readLine(prompt).toLowerCase(Locale.ROOT);
            if (input.equals("y") || input.equals("yes")) {
                return true;
            }
            if (input.equals("n") || input.equals("no")) {
                return false;
            }
            out.println("  Please answer y or n.");
        }
    }

    private String readValidated(String prompt, Function<String, String> check) {
        while (true) {
            String input = readLine(prompt);
            String error = check.apply(input);
            if (error == null) {
                return input;
            }
            out.println("  " + error);
        }
    }

    private String readValidatedOrKeep(String label, String current, Function<String, String> check) {
        while (true) {
            String input = readLine(label + " [" + current + "]: ");
            if (input.isEmpty()) {
                return current;
            }
            String error = check.apply(input);
            if (error == null) {
                return input;
            }
            out.println("  " + error);
        }
    }

    /** Reads a yyyy-MM-dd date; an empty answer returns {@code defaultValue} when it is not null. */
    private LocalDate readDate(String prompt, LocalDate defaultValue, Function<LocalDate, String> check) {
        while (true) {
            String input = readLine(prompt);
            LocalDate date;
            if (input.isEmpty()) {
                if (defaultValue == null) {
                    out.println("  A date is required (format yyyy-MM-dd).");
                    continue;
                }
                date = defaultValue;
            } else {
                try {
                    date = LocalDate.parse(input);
                } catch (DateTimeParseException e) {
                    out.println("  '" + input + "' is not a valid date - use the format yyyy-MM-dd, e.g. 2004-03-15.");
                    continue;
                }
            }
            String error = check.apply(date);
            if (error == null) {
                return date;
            }
            out.println("  " + error);
        }
    }

    /** Reads an optional GPA; an empty answer returns {@code defaultValue} (may be null). */
    private BigDecimal readGpa(String prompt, BigDecimal defaultValue) {
        while (true) {
            String input = readLine(prompt);
            if (input.isEmpty()) {
                return defaultValue;
            }
            try {
                BigDecimal gpa = new BigDecimal(input);
                String error = StudentService.checkGpa(gpa);
                if (error == null) {
                    return gpa;
                }
                out.println("  " + error);
            } catch (NumberFormatException e) {
                out.println("  '" + input + "' is not a number - enter a GPA such as 3.50.");
            }
        }
    }

    private List<Integer> readIdList(String prompt) {
        while (true) {
            List<String> parts = splitList(readLine(prompt));
            List<Integer> ids = new ArrayList<>();
            try {
                for (String part : parts) {
                    int id = Integer.parseInt(part);
                    if (id <= 0) {
                        throw new NumberFormatException(part);
                    }
                    ids.add(id);
                }
            } catch (NumberFormatException e) {
                out.println("  Enter positive whole numbers separated by commas, e.g. 1, 2, 5.");
                continue;
            }
            if (!ids.isEmpty()) {
                return ids;
            }
            out.println("  Enter at least one student ID.");
        }
    }

    private static List<String> splitList(String input) {
        List<String> values = new ArrayList<>();
        for (String part : input.split(",")) {
            if (!part.trim().isEmpty()) {
                values.add(part.trim());
            }
        }
        return values;
    }

    /** Signals that standard input has ended (e.g. Ctrl+D / end of a piped file). */
    private static final class InputClosedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InputClosedException() {
            super("Input closed");
        }
    }

    // =====================================================================
    // Logging
    // =====================================================================

    /**
     * Sends technical log records (including stack traces) to {@value #LOG_FILE} so the
     * console only shows user-facing messages.
     */
    private static void configureLogging() {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$-7s [%3$s] %5$s%6$s%n");
        Logger root = Logger.getLogger("");
        for (Handler handler : root.getHandlers()) {
            root.removeHandler(handler);
        }
        try {
            Files.createDirectories(Paths.get(LOG_FILE).getParent());
            FileHandler fileHandler = new FileHandler(LOG_FILE, true);
            fileHandler.setFormatter(new SimpleFormatter());
            root.addHandler(fileHandler);
            root.setLevel(Level.INFO);
        } catch (IOException e) {
            System.err.println("Warning: cannot write " + LOG_FILE + " (" + e.getMessage() + ") - logging to the console");
            root.addHandler(new java.util.logging.ConsoleHandler());
        }
    }
}
