package com.skillbridge.bulkupload.importer;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * {@code app.import.*}.
 *
 * @param maxRows    rows per CSV file. Each row is a bcrypt hash and three inserts, so this
 *                   bounds how long one upload occupies an import thread
 * @param staleAfter an upload whose importer has not reported progress for this long is
 *                   declared dead ({@link StaleUploadSweeper})
 * @param sweeper    whether that sweep is scheduled; off under the test profile, where a
 *                   background UPDATE lands in whatever statement count a test is measuring
 */
@Validated
@ConfigurationProperties("app.import")
public record ImportProperties(@Positive int maxRows, @NotNull Duration staleAfter, @NotNull Sweeper sweeper) {

    public record Sweeper(boolean enabled) {
    }
}
