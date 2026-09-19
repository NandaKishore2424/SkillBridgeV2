package com.skillbridge.skillgap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillbridge.common.exception.ResourceNotFoundException;
import com.skillbridge.shared.messaging.AIEventPublisher;
import com.skillbridge.student.entity.Student;
import com.skillbridge.student.repository.StudentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class SkillGapService {

    private final SkillGapReportRepository reports;
    private final StudentRepository students;
    private final AIEventPublisher aiEvents;
    private final ObjectMapper objectMapper;

    public SkillGapService(SkillGapReportRepository reports, StudentRepository students,
                           AIEventPublisher aiEvents, ObjectMapper objectMapper) {
        this.reports = reports;
        this.students = students;
        this.aiEvents = aiEvents;
        this.objectMapper = objectMapper;
    }

    /** The signed-in student's own report; empty if none has been produced yet. */
    @Transactional(readOnly = true)
    public Optional<SkillGapReportDTO> forStudentUser(Long userId) {
        return reports.findById(requireStudentOfUser(userId).getId()).map(this::toDto);
    }

    /**
     * A student's report, for an admin of the same college. Another college's
     * student is a 404, like a missing one; a student with no report yet is empty.
     */
    @Transactional(readOnly = true)
    public Optional<SkillGapReportDTO> forStudent(Long studentId, Long collegeId) {
        Student student = students.findById(studentId)
                .filter(s -> s.getCollege().getId().equals(collegeId))
                .orElseThrow(() -> ResourceNotFoundException.of("Student", studentId));
        return reports.findById(student.getId()).map(this::toDto);
    }

    /**
     * Asks the AI service to analyse the signed-in student again, through the
     * outbox, so a broker outage delays it rather than losing it. PROFILE_UPDATED
     * is the event whose meaning to the AI service is exactly that; no new event
     * type, and so no contract version, was needed.
     */
    @Transactional
    public void requestAnalysis(Long userId) {
        Student student = requireStudentOfUser(userId);
        aiEvents.publishProfileUpdated(student.getId(), student.getCollege().getId());
    }

    private Student requireStudentOfUser(Long userId) {
        return students.findByUser_Id(userId)
                .orElseThrow(() -> new ResourceNotFoundException("No student profile for this account"));
    }

    SkillGapReportDTO toDto(SkillGapReport row) {
        SkillGapReportDocument document;
        try {
            document = objectMapper.readValue(row.getReport(), SkillGapReportDocument.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("skill_gap_reports row for student " + row.getStudentId()
                    + " does not match contracts/skill-gap-report", e);
        }
        if (document.schemaVersion() != SkillGapReportDocument.SUPPORTED_VERSION) {
            // Refuse loudly rather than show a report read under the wrong shape.
            throw new IllegalStateException("skill-gap report schema version " + document.schemaVersion()
                    + " is not supported; this reader knows " + SkillGapReportDocument.SUPPORTED_VERSION);
        }
        return new SkillGapReportDTO(row.getStatus(), row.getAnalyzedAt(), document.studentSkills(),
                document.matchedJobs(), document.missingSkills());
    }
}
