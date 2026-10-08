-- =============================================================================
-- Student Management System - stored procedures
--
-- Both procedures are called from Java through CallableStatement:
--   GetStudentsByDepartment -> StudentDAO.getStudentsByDepartment()  (menu 8)
--   GetDatabaseStatistics   -> ReportService.getDatabaseTotals()     (menu 9)
--
-- Re-runnable: each procedure is dropped and recreated (procedures hold no data).
-- The DELIMITER lines are understood by the mysql client and by the
-- application's script runner (DatabaseUtils.parseSqlScript).
-- =============================================================================

DROP PROCEDURE IF EXISTS GetStudentsByDepartment;
DROP PROCEDURE IF EXISTS GetDatabaseStatistics;

DELIMITER //

-- -----------------------------------------------------------------------------
-- GetStudentsByDepartment
--   IN  p_department : department name (case-insensitive match)
--   Returns one result set with the students of that department, ordered by
--   name. Uses index idx_students_department_gpa for the WHERE clause.
--   Java: {CALL GetStudentsByDepartment(?)}
-- -----------------------------------------------------------------------------
CREATE PROCEDURE GetStudentsByDepartment(IN p_department VARCHAR(50))
    READS SQL DATA
BEGIN
    SELECT id, first_name, last_name, email, date_of_birth, department, gpa,
           enrollment_date, created_at, updated_at
    FROM students
    WHERE department = p_department
    ORDER BY last_name, first_name;
END //

-- -----------------------------------------------------------------------------
-- GetDatabaseStatistics
--   OUT p_total_students    : number of students
--   OUT p_total_courses     : number of courses
--   OUT p_total_enrollments : number of student-course enrollments
--   OUT p_average_gpa       : average GPA of students that have one (NULL if none)
--   Java: {CALL GetDatabaseStatistics(?, ?, ?, ?)} with registerOutParameter()
-- -----------------------------------------------------------------------------
CREATE PROCEDURE GetDatabaseStatistics(
    OUT p_total_students    INT,
    OUT p_total_courses     INT,
    OUT p_total_enrollments INT,
    OUT p_average_gpa       DECIMAL(3,2))
    READS SQL DATA
BEGIN
    SELECT COUNT(*), ROUND(AVG(gpa), 2)
      INTO p_total_students, p_average_gpa
      FROM students;

    SELECT COUNT(*) INTO p_total_courses FROM courses;

    SELECT COUNT(*) INTO p_total_enrollments FROM student_courses;
END //

DELIMITER ;
