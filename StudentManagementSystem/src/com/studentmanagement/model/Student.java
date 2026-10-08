package com.studentmanagement.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A student - maps to one row of the {@code students} table.
 *
 * <p>SQL to Java type mapping: INT -&gt; int, VARCHAR -&gt; String, DATE -&gt; LocalDate,
 * DECIMAL(3,2) -&gt; BigDecimal (exact, nullable), TIMESTAMP -&gt; LocalDateTime.</p>
 */
public class Student {

    private int id;                      // 0 until the row is inserted
    private String firstName;
    private String lastName;
    private String email;
    private LocalDate dateOfBirth;
    private String department;
    private BigDecimal gpa;              // null until grades are recorded
    private LocalDate enrollmentDate;
    private LocalDateTime createdAt;     // set by the database
    private LocalDateTime updatedAt;     // set by the database

    public Student() {
    }

    /** Creates a new, not yet persisted student. */
    public Student(String firstName, String lastName, String email, LocalDate dateOfBirth,
                   String department, BigDecimal gpa, LocalDate enrollmentDate) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.dateOfBirth = dateOfBirth;
        this.department = department;
        this.gpa = gpa;
        this.enrollmentDate = enrollmentDate;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    /** First and last name separated by a space. */
    public String getFullName() {
        return firstName + " " + lastName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public BigDecimal getGpa() {
        return gpa;
    }

    public void setGpa(BigDecimal gpa) {
        this.gpa = gpa;
    }

    public LocalDate getEnrollmentDate() {
        return enrollmentDate;
    }

    public void setEnrollmentDate(LocalDate enrollmentDate) {
        this.enrollmentDate = enrollmentDate;
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
        return "Student{id=" + id
                + ", firstName='" + firstName + '\''
                + ", lastName='" + lastName + '\''
                + ", email='" + email + '\''
                + ", dateOfBirth=" + dateOfBirth
                + ", department='" + department + '\''
                + ", gpa=" + gpa
                + ", enrollmentDate=" + enrollmentDate
                + ", createdAt=" + createdAt
                + ", updatedAt=" + updatedAt
                + '}';
    }
}
