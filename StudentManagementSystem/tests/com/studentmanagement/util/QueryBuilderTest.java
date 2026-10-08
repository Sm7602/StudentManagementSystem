package com.studentmanagement.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests (no database) for the parameterised query builder. */
class QueryBuilderTest {

    @Test
    void buildsASelectWithoutConditions() {
        QueryBuilder query = QueryBuilder.select("students", "id", "first_name");

        assertEquals("SELECT id, first_name FROM students", query.build());
        assertEquals(List.of(), query.getParameters());
    }

    @Test
    void combinesConditionsWithAndAndKeepsParameterOrder() {
        QueryBuilder query = QueryBuilder.select("students", "id")
                .where("first_name LIKE ? OR last_name LIKE ?", "%a%", "%a%")
                .where("department = ?", "Physics")
                .where("gpa >= ?", new BigDecimal("3.00"))
                .orderBy("last_name", true)
                .orderBy("id", false);

        assertEquals("SELECT id FROM students WHERE (first_name LIKE ? OR last_name LIKE ?) AND (department = ?) "
                + "AND (gpa >= ?) ORDER BY last_name ASC, id DESC", query.build());
        assertEquals(Arrays.asList("%a%", "%a%", "Physics", new BigDecimal("3.00")), query.getParameters());
    }

    @Test
    void whereIfSkipsTheConditionAndItsValues() {
        QueryBuilder query = QueryBuilder.select("students", "id")
                .whereIf(false, "department = ?", "ignored")
                .whereIf(true, "gpa >= ?", new BigDecimal("2.50"));

        assertEquals("SELECT id FROM students WHERE (gpa >= ?)", query.build());
        assertEquals(List.of(new BigDecimal("2.50")), query.getParameters());
    }

    @Test
    void userInputNeverBecomesPartOfTheSqlText() {
        String attack = "x' OR '1'='1'; DROP TABLE students; --";
        QueryBuilder query = QueryBuilder.select("students", "id").where("department = ?", attack);

        assertEquals("SELECT id FROM students WHERE (department = ?)", query.build());
        assertEquals(List.of(attack), query.getParameters());
    }

    @ParameterizedTest
    @ValueSource(strings = {"students; DROP TABLE x", "first name", "id--", "`id`", "1abc", ""})
    void identifiersMustBePlainNames(String identifier) {
        assertThrows(IllegalArgumentException.class, () -> QueryBuilder.select(identifier, "id"));
        assertThrows(IllegalArgumentException.class, () -> QueryBuilder.select("students", identifier));
        assertThrows(IllegalArgumentException.class, () -> QueryBuilder.select("students", "id").orderBy(identifier, true));
    }

    @Test
    void placeholderAndValueCountsMustMatch() {
        QueryBuilder query = QueryBuilder.select("students", "id");
        assertThrows(IllegalArgumentException.class, () -> query.where("department = ?"));
        assertThrows(IllegalArgumentException.class, () -> query.where("department = ?", "a", "b"));
    }

    @Test
    void conditionsCannotContainStatementSeparatorsOrComments() {
        QueryBuilder query = QueryBuilder.select("students", "id");
        assertThrows(IllegalArgumentException.class, () -> query.where("1 = 1; DELETE FROM students"));
        assertThrows(IllegalArgumentException.class, () -> query.where("1 = 1 -- comment"));
        assertThrows(IllegalArgumentException.class, () -> query.where("1 = 1 /* comment */"));
    }

    @Test
    void containsPatternEscapesLikeWildcards() {
        assertEquals("%sharma%", QueryBuilder.containsPattern("sharma"));
        assertEquals("%50\\%\\_off\\\\%", QueryBuilder.containsPattern("50%_off\\"));
    }

    @Test
    void parametersCannotBeModifiedFromOutside() {
        QueryBuilder query = QueryBuilder.select("students", "id").where("id = ?", 1);
        assertThrows(UnsupportedOperationException.class, () -> query.getParameters().add(2));
    }
}
