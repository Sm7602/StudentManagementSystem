package com.studentmanagement.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.studentmanagement.TestDatabase;
import com.studentmanagement.model.Student;

/**
 * Measures (does not assume) the effect of JDBC batching: the same number of students is
 * inserted once row by row (one auto-committed INSERT per student) and once with
 * {@link StudentDAO#addStudentsBatch}. The measured times are printed so they can be quoted
 * in the documentation; the assertions check correctness and that batching is faster.
 */
class BatchPerformanceTest {

    private static final int ROWS = 1000;

    @Test
    void batchInsertIsFasterThanRowByRowInserts() throws Exception {
        StudentDAO dao = new StudentDAO(TestDatabase.dataSource());

        TestDatabase.resetTables();
        List<Student> first = students("single");
        long rowByRowStart = System.nanoTime();
        for (Student student : first) {
            dao.addStudent(student);
        }
        long rowByRowMillis = (System.nanoTime() - rowByRowStart) / 1_000_000;
        assertEquals(ROWS, TestDatabase.countRows("students"));

        TestDatabase.resetTables();
        List<Student> second = students("batch");
        long batchStart = System.nanoTime();
        int inserted = dao.addStudentsBatch(second);
        long batchMillis = (System.nanoTime() - batchStart) / 1_000_000;
        assertEquals(ROWS, inserted);
        assertEquals(ROWS, TestDatabase.countRows("students"));

        System.out.printf("[measurement] %d inserts: row-by-row %d ms, JDBC batch %d ms%n",
                ROWS, rowByRowMillis, batchMillis);
        assertTrue(batchMillis < rowByRowMillis,
                "batch (" + batchMillis + " ms) should be faster than row-by-row (" + rowByRowMillis + " ms)");
    }

    private static List<Student> students(String prefix) {
        List<Student> students = new ArrayList<>();
        for (int i = 0; i < ROWS; i++) {
            students.add(TestDatabase.newStudent("Perf", "Student", prefix + i + "@example.com",
                    i % 2 == 0 ? "Physics" : "Mathematics", "3.00"));
        }
        return students;
    }
}
