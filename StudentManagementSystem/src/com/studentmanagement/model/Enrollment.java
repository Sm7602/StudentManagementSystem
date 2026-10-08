package com.studentmanagement.model;

import java.time.LocalDate;

/**
 * Enrollment of one student in one course - maps to one row of the
 * {@code student_courses} table, whose primary key is (student_id, course_id).
 */
public class Enrollment {

    private int studentId;
    private int courseId;
    private LocalDate enrollmentDate;
    private String grade;                // null while the course is in progress

    public Enrollment() {
    }

    public Enrollment(int studentId, int courseId, LocalDate enrollmentDate, String grade) {
        this.studentId = studentId;
        this.courseId = courseId;
        this.enrollmentDate = enrollmentDate;
        this.grade = grade;
    }

    public int getStudentId() {
        return studentId;
    }

    public void setStudentId(int studentId) {
        this.studentId = studentId;
    }

    public int getCourseId() {
        return courseId;
    }

    public void setCourseId(int courseId) {
        this.courseId = courseId;
    }

    public LocalDate getEnrollmentDate() {
        return enrollmentDate;
    }

    public void setEnrollmentDate(LocalDate enrollmentDate) {
        this.enrollmentDate = enrollmentDate;
    }

    public String getGrade() {
        return grade;
    }

    public void setGrade(String grade) {
        this.grade = grade;
    }

    @Override
    public String toString() {
        return "Enrollment{studentId=" + studentId
                + ", courseId=" + courseId
                + ", enrollmentDate=" + enrollmentDate
                + ", grade='" + grade + '\''
                + '}';
    }
}
