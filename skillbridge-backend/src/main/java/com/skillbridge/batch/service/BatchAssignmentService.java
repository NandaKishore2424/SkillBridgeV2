package com.skillbridge.batch.service;

import com.skillbridge.batch.entity.Batch;
import com.skillbridge.batch.repository.BatchRepository;
import com.skillbridge.common.exception.ResourceNotFoundException;
import com.skillbridge.common.tenant.TenantGuard;
import com.skillbridge.company.entity.Company;
import com.skillbridge.company.repository.CompanyRepository;
import com.skillbridge.trainer.entity.Trainer;
import com.skillbridge.trainer.repository.TrainerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * Attaching and detaching trainers and companies on a batch.
 *
 * <p>These relationships are reachable from both directions — "assign a trainer
 * to this batch" and "add this batch to this trainer" are the same row in
 * {@code batch_trainers}, and the UI offers both. They were being written from
 * the batch side only, in controller methods that each re-implemented the
 * lookup, so the reverse-direction endpoints the frontend calls did not exist.
 *
 * <p>Putting the logic here means the tenant check happens once rather than four
 * times, and cannot be forgotten by whichever side is added next. Every entity
 * is loaded through a guarded lookup: these endpoints take two ids from the URL,
 * and a caller who could name any trainer id could otherwise attach a trainer
 * from another college to their own batch.
 *
 * <p>Detach is idempotent — removing a trainer who is not assigned is a success,
 * not a 404. The caller's intent is "this trainer should not be on this batch",
 * and that is already true.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BatchAssignmentService {

    private final BatchRepository batchRepository;
    private final TrainerRepository trainerRepository;
    private final CompanyRepository companyRepository;

    // --- trainers -----------------------------------------------------------

    @Transactional
    public int assignTrainer(Long batchId, Long trainerId) {
        Batch batch = requireBatch(batchId);
        Trainer trainer = requireTrainer(trainerId);
        requireSameCollege(batch, trainer.getCollege().getId(), "trainer", trainerId);

        batch.getTrainers().add(trainer);
        batchRepository.save(batch);
        log.info("Assigned trainer {} to batch {}", trainerId, batchId);
        return batch.getTrainers().size();
    }

    /** Replaces the whole set, which is what the multi-select assign screen means. */
    @Transactional
    public int replaceTrainers(Long batchId, Collection<Long> trainerIds) {
        Batch batch = requireBatch(batchId);
        List<Trainer> trainers = trainerIds.stream().map(this::requireTrainer).toList();
        trainers.forEach(t -> requireSameCollege(batch, t.getCollege().getId(), "trainer", t.getId()));

        batch.getTrainers().clear();
        batch.getTrainers().addAll(trainers);
        batchRepository.save(batch);
        log.info("Set {} trainers on batch {}", trainers.size(), batchId);
        return batch.getTrainers().size();
    }

    @Transactional
    public int unassignTrainer(Long batchId, Long trainerId) {
        Batch batch = requireBatch(batchId);
        requireTrainer(trainerId);

        batch.getTrainers().removeIf(t -> t.getId().equals(trainerId));
        batchRepository.save(batch);
        log.info("Unassigned trainer {} from batch {}", trainerId, batchId);
        return batch.getTrainers().size();
    }

    // --- companies ----------------------------------------------------------

    @Transactional
    public int assignCompany(Long batchId, Long companyId) {
        Batch batch = requireBatch(batchId);
        Company company = requireCompany(companyId);
        if (company.getCollege() != null) {
            requireSameCollege(batch, company.getCollege().getId(), "company", companyId);
        }

        batch.getCompanies().add(company);
        batchRepository.save(batch);
        log.info("Linked company {} to batch {}", companyId, batchId);
        return batch.getCompanies().size();
    }

    @Transactional
    public int replaceCompanies(Long batchId, Collection<Long> companyIds) {
        Batch batch = requireBatch(batchId);
        List<Company> companies = companyIds.stream().map(this::requireCompany).toList();
        companies.stream()
                .filter(c -> c.getCollege() != null)
                .forEach(c -> requireSameCollege(batch, c.getCollege().getId(), "company", c.getId()));

        batch.getCompanies().clear();
        batch.getCompanies().addAll(companies);
        batchRepository.save(batch);
        log.info("Set {} companies on batch {}", companies.size(), batchId);
        return batch.getCompanies().size();
    }

    @Transactional
    public int unassignCompany(Long batchId, Long companyId) {
        Batch batch = requireBatch(batchId);
        requireCompany(companyId);

        batch.getCompanies().removeIf(c -> c.getId().equals(companyId));
        batchRepository.save(batch);
        log.info("Unlinked company {} from batch {}", companyId, batchId);
        return batch.getCompanies().size();
    }

    // --- guarded lookups ----------------------------------------------------

    private Batch requireBatch(Long batchId) {
        return batchRepository.findById(batchId)
                .filter(b -> TenantGuard.isVisible(b.getCollege().getId()))
                .orElseThrow(() -> ResourceNotFoundException.of("Batch", batchId));
    }

    private Trainer requireTrainer(Long trainerId) {
        return trainerRepository.findById(trainerId)
                .filter(t -> TenantGuard.isVisible(t.getCollege().getId()))
                .orElseThrow(() -> ResourceNotFoundException.of("Trainer", trainerId));
    }

    private Company requireCompany(Long companyId) {
        return companyRepository.findById(companyId)
                .filter(c -> c.getCollege() == null || TenantGuard.isVisible(c.getCollege().getId()))
                .orElseThrow(() -> ResourceNotFoundException.of("Company", companyId));
    }

    /**
     * Both sides of the relationship must belong to the same college.
     *
     * <p>The individual guards above already prove each entity is visible to the
     * caller, which for a COLLEGE_ADMIN is enough. It is not enough for a
     * SYSTEM_ADMIN, who can see every college and would otherwise be able to
     * attach one college's trainer to another college's batch — a row no tenant
     * filter would ever hide again.
     */
    private void requireSameCollege(Batch batch, Long otherCollegeId, String what, Long id) {
        if (!batch.getCollege().getId().equals(otherCollegeId)) {
            throw ResourceNotFoundException.of(what.substring(0, 1).toUpperCase() + what.substring(1), id);
        }
    }
}
