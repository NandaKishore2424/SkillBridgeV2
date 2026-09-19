package com.skillbridge.bulkupload.importer;

import com.skillbridge.bulkupload.dto.StudentUploadDTO;
import jakarta.validation.Validation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportRowMapperTest {

    private final ImportRowMapper mapper =
            new ImportRowMapper(Validation.buildDefaultValidatorFactory().getValidator());

    private static ImportRow row(String name, String email, String roll, String year) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("Full Name", name);
        values.put("Email", email);
        values.put("Roll Number", roll);
        values.put("Year", year);
        return new ImportRow(2, values, null);
    }

    @Test
    @DisplayName("a good row maps, with the email lower-cased and blanks as null")
    void maps() {
        StudentUploadDTO dto = mapper.student(row("Asha Verma", "Asha.Verma@College.EDU", "CS1", ""));

        assertThat(dto.getEmail()).isEqualTo("asha.verma@college.edu");
        assertThat(dto.getYear()).isNull();
        assertThat(dto.getDegree()).isNull();
    }

    @Test
    @DisplayName("the validation annotations run, and every reason is reported at once")
    void validates() {
        assertThatThrownBy(() -> mapper.student(row("", "not-an-email", "X".repeat(51), "9")))
                .isInstanceOf(RowRejectedException.class)
                .hasMessageContaining("Full Name is required")
                .hasMessageContaining("Email is not a valid address")
                .hasMessageContaining("Roll Number is longer than 50 characters")
                .hasMessageContaining("Year must be between 1 and 8");
    }

    @Test
    @DisplayName("a year that is not a number says so")
    void yearNotANumber() {
        assertThatThrownBy(() -> mapper.student(row("A", "a@x.edu", "R1", "three")))
                .hasMessageContaining("Year must be a whole number")
                .hasMessageContaining("three");
    }

    @Test
    @DisplayName("a ragged row is rejected with its own problem")
    void raggedRow() {
        assertThatThrownBy(() -> mapper.student(new ImportRow(3, Map.of(), "Expected 6 values, found 2.")))
                .hasMessage("Expected 6 values, found 2.");
    }
}
