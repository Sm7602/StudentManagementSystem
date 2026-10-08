-- =============================================================================
-- Student Management System - sample data
--   6 courses, 10 students, 16 enrollments
--
-- Intended for an EMPTY database. Menu option 1 (Initialize Database) loads it
-- only when all three tables are empty, inside a single transaction.
-- Enrollments are linked by e-mail / course code (not by hard-coded ids), so the
-- script works whatever AUTO_INCREMENT values the tables currently have.
-- =============================================================================

INSERT INTO courses (course_code, course_name, credits, department) VALUES
    ('CS101', 'Introduction to Programming',      4, 'Computer Science'),
    ('CS201', 'Data Structures and Algorithms',   4, 'Computer Science'),
    ('CS301', 'Database Management Systems',      3, 'Computer Science'),
    ('MA101', 'Calculus I',                       4, 'Mathematics'),
    ('PH101', 'Physics I: Mechanics',             4, 'Physics'),
    ('BA101', 'Principles of Management',         3, 'Business');

INSERT INTO students (first_name, last_name, email, date_of_birth, department, gpa, enrollment_date) VALUES
    ('Aarav',  'Sharma', 'aarav.sharma@example.com', '2003-04-12', 'Computer Science', 3.75, '2021-08-02'),
    ('Priya',  'Patel',  'priya.patel@example.com',  '2002-11-23', 'Computer Science', 3.92, '2020-08-03'),
    ('Rohan',  'Mehta',  'rohan.mehta@example.com',  '2003-07-05', 'Mathematics',      3.40, '2021-08-02'),
    ('Ananya', 'Iyer',   'ananya.iyer@example.com',  '2004-01-30', 'Physics',          3.65, '2022-08-01'),
    ('Vikram', 'Singh',  'vikram.singh@example.com', '2002-09-17', 'Business',         2.95, '2020-08-03'),
    ('Sneha',  'Reddy',  'sneha.reddy@example.com',  '2003-03-08', 'Mathematics',      3.80, '2021-08-02'),
    ('Arjun',  'Nair',   'arjun.nair@example.com',   '2004-06-21', 'Computer Science', 3.10, '2022-08-01'),
    ('Kavya',  'Gupta',  'kavya.gupta@example.com',  '2003-12-02', 'Physics',          3.55, '2021-08-02'),
    ('Rahul',  'Verma',  'rahul.verma@example.com',  '2002-05-14', 'Business',         3.20, '2020-08-03'),
    ('Meera',  'Joshi',  'meera.joshi@example.com',  '2005-02-26', 'Computer Science', NULL, '2023-08-01');

INSERT INTO student_courses (student_id, course_id, enrollment_date, grade)
SELECT s.id, c.id, e.enrollment_date, e.grade
FROM (
              SELECT 'aarav.sharma@example.com' AS email, 'CS101' AS course_code, DATE '2021-08-09' AS enrollment_date, 'A'  AS grade
    UNION ALL SELECT 'aarav.sharma@example.com', 'CS201', DATE '2022-01-10', 'A-'
    UNION ALL SELECT 'aarav.sharma@example.com', 'CS301', DATE '2023-01-09', NULL
    UNION ALL SELECT 'priya.patel@example.com',  'CS101', DATE '2020-08-10', 'A+'
    UNION ALL SELECT 'priya.patel@example.com',  'CS201', DATE '2021-01-11', 'A'
    UNION ALL SELECT 'priya.patel@example.com',  'MA101', DATE '2020-08-10', 'A'
    UNION ALL SELECT 'rohan.mehta@example.com',  'MA101', DATE '2021-08-09', 'B+'
    UNION ALL SELECT 'rohan.mehta@example.com',  'CS101', DATE '2021-08-09', 'B'
    UNION ALL SELECT 'ananya.iyer@example.com',  'PH101', DATE '2022-08-08', 'A-'
    UNION ALL SELECT 'ananya.iyer@example.com',  'MA101', DATE '2022-08-08', 'B+'
    UNION ALL SELECT 'vikram.singh@example.com', 'BA101', DATE '2020-08-10', 'B-'
    UNION ALL SELECT 'sneha.reddy@example.com',  'MA101', DATE '2021-08-09', 'A'
    UNION ALL SELECT 'arjun.nair@example.com',   'CS101', DATE '2022-08-08', 'B'
    UNION ALL SELECT 'kavya.gupta@example.com',  'PH101', DATE '2021-08-09', 'B+'
    UNION ALL SELECT 'rahul.verma@example.com',  'BA101', DATE '2020-08-10', 'B'
    UNION ALL SELECT 'meera.joshi@example.com',  'CS101', DATE '2023-08-07', NULL
) AS e
JOIN students s ON s.email = e.email
JOIN courses  c ON c.course_code = e.course_code;
