package com.skillbridge.feedback.service;

import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.repository.UserRepository;
import com.skillbridge.auth.security.AuthenticatedUser;
import com.skillbridge.batch.entity.Batch;
import com.skillbridge.batch.repository.BatchRepository;
import com.skillbridge.common.exception.BadRequestException;
import com.skillbridge.common.exception.BusinessRuleException;
import com.skillbridge.common.exception.ResourceNotFoundException;
import com.skillbridge.feedback.dto.FeedbackRequestDTO;
import com.skillbridge.feedback.dto.FeedbackResponseDTO;
import com.skillbridge.feedback.entity.Feedback;
import com.skillbridge.feedback.entity.FeedbackType;
import com.skillbridge.feedback.repository.FeedbackRepository;
import com.skillbridge.student.entity.Student;
import com.skillbridge.student.repository.StudentRepository;
import com.skillbridge.trainer.entity.Trainer;
import com.skillbridge.trainer.repository.TrainerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Feedback, stored user &#8596; user and spoken student &#8596; trainer.
 *
 * <p>The table records only {@code from_user_id} / {@code to_user_id}. The API
 * — and the two React screens on top of it — speak in <em>profile</em> ids
 * ({@code students.id}, {@code trainers.id}) and display names. Translating
 * between the two is this class's job, and it happens in exactly two places:
 * {@link #resolveTarget} on the way in, and {@link Parties} on the way out.
 *
 * <p>Three things are deliberately not trusted from the request body:
 *
 * <ul>
 *   <li><b>The author.</b> Taken from the security principal. A client that
 *       could name its own {@code fromUserId} could forge feedback.</li>
 *   <li><b>The direction.</b> Derived from who the caller actually is and who
 *       the target actually is. The old code took {@code type} from the body
 *       and then looked up whichever profile that implied, so a student could
 *       send {@code TRAINER_TO_STUDENT} and get a 404 on their own account.</li>
 *   <li><b>The tenant.</b> Every batch and every counterparty is checked
 *       against the caller's college, and a mismatch is a 404 rather than a
 *       403 — see {@link ResourceNotFoundException}.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class FeedbackServiceImpl implements FeedbackService {

    private final FeedbackRepository feedbackRepository;
    private final UserRepository userRepository;
    private final StudentRepository studentRepository;
    private final TrainerRepository trainerRepository;
    private final BatchRepository batchRepository;

    @Override
    @Transactional
    public FeedbackResponseDTO createFeedback(FeedbackRequestDTO request, AuthenticatedUser caller) {
        User author = userRepository.findById(caller.getId())
                .orElseThrow(() -> ResourceNotFoundException.of("User", caller.getId()));

        Batch batch = batchRepository.findById(request.getBatchId())
                .filter(b -> isVisibleTo(caller, b.getCollege().getId()))
                .orElseThrow(() -> ResourceNotFoundException.of("Batch", request.getBatchId()));

        Target target = resolveTarget(request, caller, batch.getCollege().getId());

        if (target.userId().equals(author.getId())) {
            throw new BadRequestException("You cannot give feedback to yourself.");
        }

        User recipient = userRepository.findById(target.userId())
                .orElseThrow(() -> ResourceNotFoundException.of("User", target.userId()));

        Feedback saved = feedbackRepository.save(Feedback.builder()
                .batch(batch)
                .fromUser(author)
                .toUser(recipient)
                .type(target.type())
                .rating(request.getRating())
                .category(request.getCategory())
                .comment(request.getComments())
                .build());

        return toDTO(saved, Parties.resolve(List.of(saved), studentRepository, trainerRepository));
    }

    @Override
    @Transactional(readOnly = true)
    public List<FeedbackResponseDTO> getFeedbackByBatch(Long batchId, AuthenticatedUser caller) {
        batchRepository.findById(batchId)
                .filter(b -> isVisibleTo(caller, b.getCollege().getId()))
                .orElseThrow(() -> ResourceNotFoundException.of("Batch", batchId));

        return map(feedbackRepository.findByBatch(batchId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<FeedbackResponseDTO> getFeedbackAboutStudent(Long studentId, AuthenticatedUser caller) {
        Student student = studentRepository.findById(studentId)
                .filter(s -> isVisibleTo(caller, s.getCollege().getId()))
                .orElseThrow(() -> ResourceNotFoundException.of("Student", studentId));

        return map(feedbackRepository.findReceivedBy(student.getUser().getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<FeedbackResponseDTO> getMyFeedback(AuthenticatedUser caller) {
        return map(feedbackRepository.findInvolving(caller.getId()));
    }

    // --- inbound translation ------------------------------------------------

    /** Who the feedback is about, as a user id, plus the direction that implies. */
    private record Target(Long userId, FeedbackType type) {
    }

    /**
     * Turns the request's profile id into a user id and decides the direction.
     *
     * <p>The caller's own role picks the branch, so a student can only ever
     * write {@code STUDENT_TO_TRAINER} and a trainer only
     * {@code TRAINER_TO_STUDENT} — the only two values
     * {@code feedback_feedback_type_check} permits. A user who is somehow both
     * is treated as a student, since that is the narrower privilege.
     */
    private Target resolveTarget(FeedbackRequestDTO request, AuthenticatedUser caller, Long batchCollegeId) {
        if (caller.hasRole("STUDENT")) {
            Long trainerId = require(request.getTrainerId(), "trainerId is required when a student gives feedback.");
            Trainer trainer = trainerRepository.findById(trainerId)
                    .filter(t -> t.getCollege().getId().equals(batchCollegeId))
                    .orElseThrow(() -> ResourceNotFoundException.of("Trainer", trainerId));
            return new Target(trainer.getUser().getId(), FeedbackType.STUDENT_TO_TRAINER);
        }

        if (caller.hasRole("TRAINER")) {
            Long studentId = require(request.getStudentId(), "studentId is required when a trainer gives feedback.");
            Student student = studentRepository.findById(studentId)
                    .filter(s -> s.getCollege().getId().equals(batchCollegeId))
                    .orElseThrow(() -> ResourceNotFoundException.of("Student", studentId));
            return new Target(student.getUser().getId(), FeedbackType.TRAINER_TO_STUDENT);
        }

        throw new BusinessRuleException(
                "Only students and trainers can give feedback.");
    }

    private static Long require(Long value, String message) {
        if (value == null) {
            throw new BadRequestException(message);
        }
        return value;
    }

    /**
     * A SYSTEM_ADMIN sees every tenant; everyone else sees only their own.
     * Returns false for a college-less non-admin rather than NPE-ing on the
     * comparison.
     */
    private static boolean isVisibleTo(AuthenticatedUser caller, Long collegeId) {
        if (caller.isSystemAdmin()) {
            return true;
        }
        return collegeId != null && collegeId.equals(caller.getCollegeId());
    }

    // --- outbound translation ----------------------------------------------

    private List<FeedbackResponseDTO> map(List<Feedback> rows) {
        Parties parties = Parties.resolve(rows, studentRepository, trainerRepository);
        return rows.stream().map(row -> toDTO(row, parties)).collect(Collectors.toList());
    }

    /**
     * Profile lookups for a whole page of feedback, in two queries.
     *
     * <p>Resolving each row on its own would be four extra selects per row.
     * Here every user id on the page is collected once and looked up in bulk.
     */
    private record Parties(Map<Long, Student> studentsByUserId, Map<Long, Trainer> trainersByUserId) {

        static Parties resolve(List<Feedback> rows, StudentRepository students, TrainerRepository trainers) {
            if (rows.isEmpty()) {
                return new Parties(Map.of(), Map.of());
            }
            Set<Long> userIds = new HashSet<>();
            for (Feedback row : rows) {
                userIds.add(row.getFromUser().getId());
                userIds.add(row.getToUser().getId());
            }
            return new Parties(
                    index(students.findByUser_IdIn(userIds), s -> s.getUser().getId()),
                    index(trainers.findByUser_IdIn(userIds), t -> t.getUser().getId()));
        }

        private static <T> Map<Long, T> index(Collection<T> rows, Function<T, Long> key) {
            return rows.stream().collect(Collectors.toMap(key, Function.identity(), (a, b) -> a));
        }
    }

    /**
     * Renders one row in the student/trainer vocabulary the API has always used.
     *
     * <p>{@code type} says which end is which: on {@code STUDENT_TO_TRAINER} the
     * author is the student, on {@code TRAINER_TO_STUDENT} the recipient is.
     * Either profile may be absent — the row references users, and a user's
     * profile can be deleted or never created — so the ids and names are left
     * null rather than failing the whole response.
     */
    private FeedbackResponseDTO toDTO(Feedback row, Parties parties) {
        boolean fromStudent = row.getType() == FeedbackType.STUDENT_TO_TRAINER;
        Long studentUserId = fromStudent ? row.getFromUser().getId() : row.getToUser().getId();
        Long trainerUserId = fromStudent ? row.getToUser().getId() : row.getFromUser().getId();

        Student student = parties.studentsByUserId().get(studentUserId);
        Trainer trainer = parties.trainersByUserId().get(trainerUserId);

        return FeedbackResponseDTO.builder()
                .id(row.getId())
                .studentId(student != null ? student.getId() : null)
                .studentName(student != null ? student.getFullName() : null)
                .trainerId(trainer != null ? trainer.getId() : null)
                .trainerName(trainer != null ? trainer.getFullName() : null)
                .batchId(row.getBatch().getId())
                .batchName(row.getBatch().getName())
                .type(row.getType())
                .rating(row.getRating())
                .category(row.getCategory())
                .comments(row.getComment())
                .createdAt(row.getCreatedAt())
                .build();
    }
}
