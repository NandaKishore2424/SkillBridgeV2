package com.skillbridge.skillgap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reader's half of contracts/skill-gap-report/v1. The AI service's
 * tests/test_skill_gap_report_contract.py checks that what it writes validates
 * against the same schema; this checks that the schema and its examples are
 * what {@link SkillGapReportDocument} reads.
 *
 * <p>A plain ObjectMapper, which fails on unknown properties, so a field the
 * writer adds without this reader learning it is a red test here, not a value
 * silently dropped on the way to the screen.
 */
class SkillGapReportContractTest {

    private static final Path CONTRACT = contractsDir().resolve("skill-gap-report/v1");
    private final ObjectMapper strict = new ObjectMapper();

    @Test
    @DisplayName("the examples read into the document record, every value intact")
    void examplesParse() throws IOException {
        SkillGapReportDocument success = strict.readValue(
                CONTRACT.resolve("success.example.json").toFile(), SkillGapReportDocument.class);
        assertThat(success.status()).isEqualTo("SUCCESS");
        assertThat(success.matchedJobs()).hasSize(2);
        assertThat(success.matchedJobs().get(0).similarity()).isEqualTo(0.624);
        assertThat(success.matchedJobs().get(1).company()).isNull();
        assertThat(success.missingSkills()).containsExactly("hadoop", "python", "spark");

        SkillGapReportDocument skipped = strict.readValue(
                CONTRACT.resolve("skipped.example.json").toFile(), SkillGapReportDocument.class);
        assertThat(skipped.status()).isEqualTo("SKIPPED");
        assertThat(skipped.matchedJobs()).isEmpty();
    }

    @Test
    @DisplayName("the record's fields are exactly the schema's properties, at both levels")
    void fieldsMatchSchema() throws IOException {
        JsonNode schema = strict.readTree(CONTRACT.resolve("skill-gap-report.schema.json").toFile());

        assertThat(components(SkillGapReportDocument.class)).isEqualTo(names(schema.path("properties")));
        assertThat(components(SkillGapReportDocument.MatchedJob.class))
                .isEqualTo(names(schema.path("properties").path("matchedJobs").path("items").path("properties")));
        assertThat(schema.path("properties").path("schemaVersion").path("const").asInt())
                .isEqualTo(SkillGapReportDocument.SUPPORTED_VERSION);
    }

    private static Set<String> components(Class<? extends Record> type) {
        Set<String> names = new HashSet<>();
        Arrays.stream(type.getRecordComponents()).forEach(c -> names.add(c.getName()));
        return names;
    }

    private static Set<String> names(JsonNode properties) {
        Set<String> names = new HashSet<>();
        properties.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Path contractsDir() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("contracts"))) {
                return p.resolve("contracts");
            }
        }
        throw new IllegalStateException("could not find contracts/ above " + Path.of("").toAbsolutePath());
    }
}
