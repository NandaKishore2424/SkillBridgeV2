package com.skillbridge.feedback.entity;

import com.skillbridge.auth.entity.User;
import com.skillbridge.batch.entity.Batch;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One piece of feedback, from one user to another, within a batch.
 *
 * <p>This entity used to declare {@code @Table(name = "feedbacks")} — a table
 * no migration in this repository has ever created. The real table is
 * {@code feedback}, created by V1, and it models feedback as
 * <em>user &#8596; user</em> ({@code from_user_id} / {@code to_user_id}) rather
 * than student &#8596; trainer. The entity has therefore never been able to
 * persist a row, in any environment; {@code ddl-auto: validate} was the only
 * thing that surfaced it.
 *
 * <p>The user &#8596; user shape is kept deliberately: it is what the database
 * already holds, and it leaves room for trainer &#8596; trainer or admin
 * &#8596; trainer feedback later without another migration. The
 * student/trainer vocabulary the API speaks is a translation applied in
 * {@code FeedbackServiceImpl}, not a property of the storage model.
 *
 * <p>Two database CHECK constraints back the fields here and must not be
 * contradicted: {@code rating} is 1–5, and {@code feedback_type} is one of
 * {@code TRAINER_TO_STUDENT} / {@code STUDENT_TO_TRAINER}.
 */
@Entity
@Table(name = "feedback")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Feedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private Batch batch;

    /** The author. Always the authenticated caller — never client-supplied. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_user_id", nullable = false)
    private User fromUser;

    /** The subject of the feedback. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_user_id", nullable = false)
    private User toUser;

    /**
     * The direction, derived from the two parties' roles rather than trusted
     * from the request body. Constrained by {@code feedback_feedback_type_check}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "feedback_type", nullable = false, length = 20)
    private FeedbackType type;

    /** 1–5. Constrained by {@code feedback_rating_check}. */
    @Column(name = "rating", nullable = false)
    private Integer rating;

    /**
     * Free-text label such as "Teaching Quality" or "Punctuality". Added by V18
     * — V1 had no such column, so this is nullable and older rows have none.
     */
    @Column(name = "category", length = 100)
    private String category;

    /** Singular in the database; the API exposes it as {@code comments}. */
    @Column(name = "comment", columnDefinition = "TEXT")
    private String comment;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
