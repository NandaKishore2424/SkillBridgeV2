package com.skillbridge.skillgap;

import java.time.LocalDateTime;
import java.util.List;

/**
 * What a screen shows. {@code null} from the endpoint (204) means no analysis
 * has run yet, which is different from SKIPPED: the student has no skills.
 *
 * @param similarity per job, cosine similarity, -1 to 1; the screen shows it as a percentage
 */
public record SkillGapReportDTO(String status, LocalDateTime analyzedAt, List<String> studentSkills,
                                List<SkillGapReportDocument.MatchedJob> matchedJobs, List<String> missingSkills) {
}
