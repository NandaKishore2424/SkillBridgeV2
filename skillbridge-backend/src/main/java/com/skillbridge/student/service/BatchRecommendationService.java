package com.skillbridge.student.service;

import com.skillbridge.batch.entity.Batch;
import com.skillbridge.batch.repository.BatchRepository;
import com.skillbridge.enrollment.repository.EnrollmentRepository;
import com.skillbridge.student.dto.RecommendedBatchDTO;
import com.skillbridge.student.entity.Student;
import com.skillbridge.student.entity.StudentSkill;
import com.skillbridge.syllabus.repository.SyllabusModuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Scores open batches against a student's profile.
 *
 * <p>Deliberately a transparent, explainable heuristic rather than an opaque
 * model. Three reasons:
 *
 * <ul>
 *   <li>It needs no training data, and there is none — nobody has enrolled
 *       through a recommendation yet, so there is nothing to learn from.</li>
 *   <li>Every recommendation carries a human-readable reason. A score with no
 *       explanation is not a recommendation, it is a number.</li>
 *   <li>It gives the eventual embedding-based version something to be measured
 *       against. "Better than what?" is otherwise unanswerable.</li>
 * </ul>
 *
 * <p>The intended evolution is to replace the skill-overlap term with cosine
 * similarity over the same MiniLM vectors the job corpus already uses, and keep
 * this as the fallback for when the AI service is unavailable.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class BatchRecommendationService {

    private static final double W_SKILL_OVERLAP = 0.45;
    private static final double W_BRANCH_MATCH = 0.25;
    private static final double W_POPULARITY = 0.15;
    private static final double W_RECENCY = 0.15;

    /** Below this, a batch is noise and is not worth putting in front of anyone. */
    private static final double MIN_SCORE = 0.15;

    private final BatchRepository batchRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final SyllabusModuleRepository moduleRepository;

    public List<RecommendedBatchDTO> recommend(Student student, int limit) {
        if (student.getCollege() == null) {
            return List.of();
        }

        // An empty IN () is a syntax error, so a sentinel stands in for "none".
        List<Long> alreadyIn = new ArrayList<>(
                enrollmentRepository.findBatchIdsByStudentId(student.getId()));
        if (alreadyIn.isEmpty()) {
            alreadyIn.add(-1L);
        }

        List<Batch> candidates = batchRepository.findOpenForCollegeExcluding(
                student.getCollege().getId(), alreadyIn);

        if (candidates.isEmpty()) {
            return List.of();
        }

        List<Long> candidateIds = candidates.stream().map(Batch::getId).toList();

        Set<String> studentSkills = student.getSkills().stream()
                .map(StudentSkill::getSkill)
                .filter(java.util.Objects::nonNull)
                .map(skill -> skill.getName().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        // Both of these are batch-loaded. Fetching per candidate inside the
        // scoring loop is the classic way a recommender becomes the slowest
        // endpoint in an application.
        Map<Long, Set<String>> batchKeywords = loadCurriculumKeywords(candidateIds);
        Map<Long, Integer> enrollmentCounts = loadEnrollmentCounts(candidateIds);

        int maxEnrolled = enrollmentCounts.values().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);

        return candidates.stream()
                .map(batch -> score(batch, student, studentSkills,
                        batchKeywords.getOrDefault(batch.getId(), Set.of()),
                        enrollmentCounts.getOrDefault(batch.getId(), 0),
                        maxEnrolled))
                .filter(r -> r.getMatchScore() >= MIN_SCORE)
                .sorted(Comparator.comparingDouble(RecommendedBatchDTO::getMatchScore).reversed())
                .limit(limit)
                .toList();
    }

    // ------------------------------------------------------------------

    private RecommendedBatchDTO score(Batch batch, Student student, Set<String> studentSkills,
                                      Set<String> batchKeywords, int enrolled, int maxEnrolled) {

        // Overlap normalised by what the batch teaches rather than by what the
        // student knows, so a broad syllabus is not unfairly favoured simply for
        // covering more ground.
        Set<String> overlap = new HashSet<>(studentSkills);
        overlap.retainAll(batchKeywords);
        double skillScore = batchKeywords.isEmpty()
                ? 0.0
                : (double) overlap.size() / batchKeywords.size();

        boolean branchMatch = matchesBranch(batch, student);
        double popularity = maxEnrolled == 0 ? 0.0 : (double) enrolled / maxEnrolled;

        long daysToStart = batch.getStartDate() == null
                ? 365
                : ChronoUnit.DAYS.between(LocalDate.now(), batch.getStartDate());
        // Already started scores zero; within about three months tapers linearly.
        double recency = daysToStart < 0 ? 0.0 : Math.max(0.0, 1 - (daysToStart / 90.0));

        double total = W_SKILL_OVERLAP * skillScore
                + W_BRANCH_MATCH * (branchMatch ? 1.0 : 0.0)
                + W_POPULARITY * popularity
                + W_RECENCY * recency;

        RecommendedBatchDTO dto = new RecommendedBatchDTO();
        dto.setId(batch.getId());
        dto.setName(batch.getName());
        dto.setDescription(batch.getDescription());
        dto.setStatus(batch.getStatus());
        dto.setStartDate(batch.getStartDate());
        dto.setEndDate(batch.getEndDate());
        dto.setCollegeId(batch.getCollege() == null ? null : batch.getCollege().getId());
        dto.setMatchScore(Math.round(total * 100) / 100.0);
        dto.setMatchReasons(explain(overlap, branchMatch, recency, enrolled));
        dto.setEnrolledCount(enrolled);
        dto.setMaxEnrollments(batch.getCapacity());
        return dto;
    }

    /**
     * Always say why.
     *
     * <p>Ordered most-specific first, because the first reason is the one a
     * student actually reads.
     */
    private List<String> explain(Set<String> overlap, boolean branchMatch,
                                 double recency, int enrolled) {
        List<String> reasons = new ArrayList<>();

        if (!overlap.isEmpty()) {
            reasons.add("Builds on your " + String.join(", ", overlap.stream().limit(3).toList())
                    + " experience");
        }
        if (branchMatch) {
            reasons.add("Matches your branch of study");
        }
        if (recency > 0.7) {
            reasons.add("Starting soon");
        }
        if (enrolled > 0) {
            reasons.add(enrolled + " students from your college have joined");
        }
        if (reasons.isEmpty()) {
            reasons.add("Open for enrollment in your college");
        }
        return reasons;
    }

    /**
     * Whether the batch looks aimed at this student's branch.
     *
     * <p>A substring match against the name and description, which is crude but
     * honest: there is no branch field on a batch to match properly, and
     * inventing a taxonomy nobody populates would be worse than a keyword test
     * that is right most of the time and contributes a quarter of the score.
     */
    private boolean matchesBranch(Batch batch, Student student) {
        String branch = student.getBranch();
        if (branch == null || branch.isBlank()) {
            return false;
        }
        String needle = branch.toLowerCase(Locale.ROOT);
        String haystack = ((batch.getName() == null ? "" : batch.getName()) + " "
                + (batch.getDescription() == null ? "" : batch.getDescription()))
                .toLowerCase(Locale.ROOT);
        return haystack.contains(needle);
    }

    /**
     * Skill keywords each candidate batch teaches, derived from its module names.
     *
     * <p>Module titles are the closest thing the schema has to a statement of what
     * a batch covers. Splitting them into words is approximate, but it is real
     * data maintained by the people who build the curriculum, which beats a
     * separate tag list that would go stale immediately.
     */
    private Map<Long, Set<String>> loadCurriculumKeywords(List<Long> batchIds) {
        Map<Long, Set<String>> keywords = new HashMap<>();

        for (Object[] row : moduleRepository.findModuleNamesByBatchIds(batchIds)) {
            Long batchId = (Long) row[0];
            String moduleName = (String) row[1];
            if (moduleName == null) {
                continue;
            }
            Set<String> bucket = keywords.computeIfAbsent(batchId, k -> new HashSet<>());
            for (String word : moduleName.toLowerCase(Locale.ROOT).split("[^a-z0-9+#.]+")) {
                // Two characters is enough for "go", "c#", "js"; one is noise.
                if (word.length() >= 2) {
                    bucket.add(word);
                }
            }
        }
        return keywords;
    }

    private Map<Long, Integer> loadEnrollmentCounts(List<Long> batchIds) {
        Map<Long, Integer> counts = new HashMap<>();
        for (Object[] row : enrollmentRepository.countGroupedByBatchIds(batchIds)) {
            counts.put((Long) row[0], ((Number) row[1]).intValue());
        }
        return counts;
    }
}
