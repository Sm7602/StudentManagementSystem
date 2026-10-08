package com.studentmanagement.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Small builder for parameterised SELECT statements with optional filters.
 *
 * <p>Used by {@code StudentDAO.searchStudents}, where any combination of keyword,
 * department and minimum GPA can be given. Safety rules:</p>
 * <ul>
 *   <li>Values are never put into the SQL text: every condition uses {@code ?}
 *       placeholders and the values are collected in {@link #getParameters()} for a
 *       {@link java.sql.PreparedStatement}.</li>
 *   <li>Table and column names must be plain identifiers, so they cannot carry SQL.</li>
 *   <li>Conditions are fixed fragments written in the code; the number of
 *       placeholders must match the number of values supplied.</li>
 * </ul>
 */
public final class QueryBuilder {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final String table;
    private final List<String> columns;
    private final List<String> conditions = new ArrayList<>();
    private final List<Object> parameters = new ArrayList<>();
    private final List<String> orderBy = new ArrayList<>();

    private QueryBuilder(String table, List<String> columns) {
        this.table = table;
        this.columns = columns;
    }

    /** Starts {@code SELECT <columns> FROM <table>}. */
    public static QueryBuilder select(String table, String... columns) {
        requireIdentifier(table);
        if (columns.length == 0) {
            throw new IllegalArgumentException("At least one column is required");
        }
        for (String column : columns) {
            requireIdentifier(column);
        }
        return new QueryBuilder(table, Arrays.asList(columns.clone()));
    }

    /**
     * Adds a condition (combined with AND). The condition must use one {@code ?} per value.
     *
     * @param condition SQL predicate written in the code, e.g. {@code "gpa >= ?"}
     * @param values    values bound to the placeholders, in order
     */
    public QueryBuilder where(String condition, Object... values) {
        Objects.requireNonNull(condition, "condition");
        if (condition.indexOf(';') >= 0 || condition.contains("--") || condition.contains("/*")) {
            throw new IllegalArgumentException("A condition must be a single SQL predicate: " + condition);
        }
        long placeholders = condition.chars().filter(ch -> ch == '?').count();
        if (placeholders != values.length) {
            throw new IllegalArgumentException("Condition '" + condition + "' has " + placeholders
                    + " placeholder(s) but " + values.length + " value(s) were supplied");
        }
        conditions.add(condition);
        parameters.addAll(Arrays.asList(values));
        return this;
    }

    /** Adds the condition only when {@code include} is true (optional search filters). */
    public QueryBuilder whereIf(boolean include, String condition, Object... values) {
        return include ? where(condition, values) : this;
    }

    /** Adds an ORDER BY column. */
    public QueryBuilder orderBy(String column, boolean ascending) {
        requireIdentifier(column);
        orderBy.add(column + (ascending ? " ASC" : " DESC"));
        return this;
    }

    /** Returns the SQL text with {@code ?} placeholders. */
    public String build() {
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(String.join(", ", columns))
                .append(" FROM ").append(table);
        for (int i = 0; i < conditions.size(); i++) {
            sql.append(i == 0 ? " WHERE " : " AND ").append('(').append(conditions.get(i)).append(')');
        }
        if (!orderBy.isEmpty()) {
            sql.append(" ORDER BY ").append(String.join(", ", orderBy));
        }
        return sql.toString();
    }

    /** Values for the placeholders of {@link #build()}, in order. */
    public List<Object> getParameters() {
        return Collections.unmodifiableList(new ArrayList<>(parameters));
    }

    /**
     * Turns user text into a LIKE pattern meaning "contains": the LIKE wildcards
     * {@code %} and {@code _} (and the escape character {@code \}) are escaped so that
     * they match literally, then the value is wrapped in {@code %...%}.
     */
    public static String containsPattern(String value) {
        StringBuilder pattern = new StringBuilder(value.length() + 2).append('%');
        for (char c : value.toCharArray()) {
            if (c == '\\' || c == '%' || c == '_') {
                pattern.append('\\');
            }
            pattern.append(c);
        }
        return pattern.append('%').toString();
    }

    private static void requireIdentifier(String name) {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("Not a valid SQL identifier: " + name);
        }
    }
}
