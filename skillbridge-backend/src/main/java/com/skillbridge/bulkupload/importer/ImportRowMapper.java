package com.skillbridge.bulkupload.importer;

import com.skillbridge.bulkupload.dto.StudentUploadDTO;
import com.skillbridge.bulkupload.dto.TrainerUploadDTO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns a CSV row into a validated DTO, or rejects it with every reason at
 * once. The old importer declared {@code @Email} and {@code @NotBlank} on these
 * DTOs and never ran them.
 */
@Component
public class ImportRowMapper {

    private final Validator validator;

    public ImportRowMapper(Validator validator) {
        this.validator = validator;
    }

    public StudentUploadDTO student(ImportRow row) {
        requireReadable(row);
        return valid(StudentUploadDTO.builder()
                .fullName(text(row, "Full Name"))
                .email(email(row))
                .rollNumber(text(row, "Roll Number"))
                .degree(text(row, "Degree"))
                .branch(text(row, "Branch"))
                .year(wholeNumber(row, "Year"))
                .build());
    }

    public TrainerUploadDTO trainer(ImportRow row) {
        requireReadable(row);
        return valid(TrainerUploadDTO.builder()
                .fullName(text(row, "Full Name"))
                .email(email(row))
                .department(text(row, "Department"))
                .specialization(text(row, "Specialization"))
                .build());
    }

    private static void requireReadable(ImportRow row) {
        if (row.problem() != null) {
            throw new RowRejectedException(row.problem());
        }
    }

    private <T> T valid(T dto) {
        Set<ConstraintViolation<T>> violations = validator.validate(dto);
        if (!violations.isEmpty()) {
            throw new RowRejectedException(violations.stream()
                    .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                    .map(ConstraintViolation::getMessage)
                    .distinct()
                    .collect(Collectors.joining("; ")));
        }
        return dto;
    }

    /** Blank is absent, so an empty optional cell stores NULL rather than "". */
    private static String text(ImportRow row, String column) {
        String value = row.values().get(column);
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Lower-cased. Emails are unique in {@code users} but compared exactly, and
     * every existing address is lower case (checked 2026-09-19), so a mixed-case
     * import would create a second account for the same person.
     */
    private static String email(ImportRow row) {
        String value = text(row, "Email");
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static Integer wholeNumber(ImportRow row, String column) {
        String value = text(row, column);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            throw new RowRejectedException(column + " must be a whole number, like 3; found \"" + value + "\"");
        }
    }
}
