-- =============================================================================
-- One-step installation with the mysql command-line client.
-- Run from the PROJECT ROOT (the SOURCE paths below are relative to it):
--
--   mysql -u root -p < database/install.sql
--
-- Creates the student_management database, then the tables/indexes, the stored
-- procedures and the sample data. Intended for a fresh installation; to start
-- over, first run:  DROP DATABASE student_management;
--
-- The Java application performs the same steps from menu option 1
-- (Initialize Database), so using this script is optional.
-- =============================================================================

CREATE DATABASE IF NOT EXISTS student_management
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE student_management;

SOURCE database/schema.sql;
SOURCE database/procedures.sql;
SOURCE database/seed.sql;
