package com.studentmanagement.model;

import java.time.LocalDateTime;

/**
 * A course - maps to one row of the {@code courses} table.
 */
public class Course {

    private int id;                      // 0 until the row is inserted
    private String courseCode;
    private String courseName;
    private int credits;
    private String department;
    private LocalDateTime createdAt;     // set by the database
    private LocalDateTime updatedAt;     // set by the database

    public Course() {
    }

    /** Creates a new, not yet persisted course. */
    public Course(String courseCode, String courseName, int credits, String department) {
        this.courseCode = courseCode;
        this.courseName = courseName;
        this.credits = credits;
        this.department = department;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getCourseCode() {
        return courseCode;
    }

    public void setCourseCode(String courseCode) {
        this.courseCode = courseCode;
    }

    public String getCourseName() {
        return courseName;
    }

    public void setCourseName(String courseName) {
        this.courseName = courseName;
    }

    public int getCredits() {
        return credits;
    }

    public void setCredits(int credits) {
        this.credits = credits;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return "Course{id=" + id
                + ", courseCode='" + courseCode + '\''
                + ", courseName='" + courseName + '\''
                + ", credits=" + credits
                + ", department='" + department + '\''
                + ", createdAt=" + createdAt
                + ", updatedAt=" + updatedAt
                + '}';
    }
}
