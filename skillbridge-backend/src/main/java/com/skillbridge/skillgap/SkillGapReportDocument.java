package com.skillbridge.skillgap;

import java.util.List;

/**
 * contracts/skill-gap-report/v1, as this application reads it. The AI service
 * writes it (report_store.py). {@code SkillGapReportContractTest} parses the
 * contract's examples into this record, so a change on either side that the
 * other cannot read turns a test red.
 */
public record SkillGapReportDocument(
        int schemaVersion,
        long studentId,
        String status,
        List<String> studentSkills,
        List<MatchedJob> matchedJobs,
        List<String> missingSkills,
        String analyzedAt) {

    /** The versions this reader understands. */
    public static final int SUPPORTED_VERSION = 1;

    public record MatchedJob(String title, String company, double similarity,
                             List<String> matchedSkills, List<String> missingSkills) {
    }
}
