-- =============================================================================
-- Student Management System - database schema (MySQL 8.0.16 or newer)
--
-- Creates the three tables with their keys, constraints and indexes.
-- The script is idempotent (CREATE TABLE IF NOT EXISTS) and never deletes data,
-- so it is safe to run again on an existing database.
--
-- Executed automatically by menu option 1 (Initialize Database), or manually:
--   mysql -u root -p student_management < database/schema.sql
-- (database/install.sql creates the database and runs all scripts in one go.)
--
-- Tables inherit the database character set / collation (utf8mb4), so table
-- columns and stored-procedure parameters always use the same collation.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- students: one row per student
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS students (
    id              INT          NOT NULL AUTO_INCREMENT,
    first_name      VARCHAR(50)  NOT NULL,
    last_name       VARCHAR(50)  NOT NULL,
    email           VARCHAR(100) NOT NULL,
    date_of_birth   DATE         NOT NULL,
    department      VARCHAR(50)  NOT NULL,
    gpa             DECIMAL(3,2) NULL,                      -- NULL until the first grades are recorded
    enrollment_date DATE         NOT NULL DEFAULT (CURRENT_DATE),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    CONSTRAINT uk_students_email UNIQUE (email),
    CONSTRAINT chk_students_gpa   CHECK (gpa IS NULL OR gpa BETWEEN 0.00 AND 4.00),
    CONSTRAINT chk_students_dates CHECK (enrollment_date > date_of_birth),

    -- Composite index used by:
    --   * GetStudentsByDepartment ............ WHERE department = ?
    --   * search screen ...................... WHERE department = ? AND gpa >= ?
    --   * statistics report .................. GROUP BY department with AVG(gpa)
    --     (answered from the index alone - EXPLAIN shows "Using index")
    INDEX idx_students_department_gpa (department, gpa)
) ENGINE = InnoDB;

-- -----------------------------------------------------------------------------
-- courses: catalogue of courses offered
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS courses (
    id          INT          NOT NULL AUTO_INCREMENT,
    course_code VARCHAR(20)  NOT NULL,
    course_name VARCHAR(100) NOT NULL,
    credits     TINYINT      NOT NULL,
    department  VARCHAR(50)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    CONSTRAINT uk_courses_code     UNIQUE (course_code),   -- also serves lookups by course code
    CONSTRAINT chk_courses_credits CHECK (credits BETWEEN 1 AND 10)
) ENGINE = InnoDB;

-- -----------------------------------------------------------------------------
-- student_courses: enrollment relationship (many-to-many students <-> courses)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS student_courses (
    student_id      INT        NOT NULL,
    course_id       INT        NOT NULL,
    enrollment_date DATE       NOT NULL DEFAULT (CURRENT_DATE),
    grade           VARCHAR(2) NULL,                         -- NULL while the course is in progress

    -- Composite primary key: a student can enrol in a course only once.
    -- Its leftmost column also serves "all enrollments of a student".
    PRIMARY KEY (student_id, course_id),

    -- Serves "all enrollments of a course", the per-course report and the
    -- foreign-key check when a course is deleted.
    INDEX idx_student_courses_course (course_id),

    -- Deleting a student removes their enrollments automatically.
    CONSTRAINT fk_student_courses_student FOREIGN KEY (student_id)
        REFERENCES students (id) ON DELETE CASCADE,
    -- A course that still has enrolled students cannot be deleted.
    CONSTRAINT fk_student_courses_course FOREIGN KEY (course_id)
        REFERENCES courses (id) ON DELETE RESTRICT,
    CONSTRAINT chk_student_courses_grade CHECK (
        grade IS NULL OR grade IN ('A+', 'A', 'A-', 'B+', 'B', 'B-', 'C+', 'C', 'C-', 'D', 'F'))
) ENGINE = InnoDB;
