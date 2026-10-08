-- =============================================================================
-- OPTIONAL - dedicated application account (recommended instead of root).
--
-- 1. Replace the password below with your own.
-- 2. Run as an administrative user:   mysql -u root -p < database/create_app_user.sql
-- 3. Set  db.username=sms_user  in config/database.properties and pass the
--    password through the DB_PASSWORD environment variable (see README).
--
-- The account only receives privileges on the application's own schemas.
-- =============================================================================

CREATE USER IF NOT EXISTS 'sms_user'@'localhost' IDENTIFIED BY 'Change_Me_2026!';

-- Application database (tables, stored procedures, backup with mysqldump).
GRANT ALL PRIVILEGES ON student_management.* TO 'sms_user'@'localhost';

-- Separate database used only by the automated tests (run-tests script).
GRANT ALL PRIVILEGES ON student_management_test.* TO 'sms_user'@'localhost';
