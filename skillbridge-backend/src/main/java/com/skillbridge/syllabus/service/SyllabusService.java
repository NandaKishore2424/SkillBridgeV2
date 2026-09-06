package com.skillbridge.syllabus.service;

import com.skillbridge.batch.entity.Batch;
import com.skillbridge.batch.repository.BatchRepository;
import com.skillbridge.syllabus.dto.*;
import com.skillbridge.syllabus.entity.SyllabusModule;
import com.skillbridge.syllabus.entity.SyllabusSubmodule;
import com.skillbridge.syllabus.entity.SyllabusTopic;
import com.skillbridge.syllabus.repository.SyllabusModuleRepository;
import com.skillbridge.syllabus.repository.SyllabusSubmoduleRepository;
import com.skillbridge.syllabus.repository.SyllabusTopicRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import com.skillbridge.common.exception.BusinessRuleException;
import com.skillbridge.common.exception.ConflictException;
import com.skillbridge.common.exception.ResourceNotFoundException;
import com.skillbridge.common.tenant.TenantGuard;

/**
 * Service for managing batch curriculum (modules, sub-modules, and topics)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SyllabusService {

    private final SyllabusModuleRepository moduleRepository;
    private final SyllabusSubmoduleRepository submoduleRepository;
    private final SyllabusTopicRepository topicRepository;
    private final BatchRepository batchRepository;

    // ===================================================================
    // CURRICULUM (Full 3-level structure)
    // ===================================================================

    /**
     * Get complete curriculum for a batch (modules -> submodules -> topics)
     */
    @Transactional(readOnly = true)
    public List<SyllabusModuleDTO> getCurriculumByBatchId(Long batchId) {
        log.info("Fetching curriculum for batch {}", batchId);
        requireBatch(batchId);

        List<SyllabusModule> modules = moduleRepository.findByBatchIdWithSubmodulesAndTopics(batchId);

        return modules.stream()
                .map(this::convertToModuleDTO)
                .collect(Collectors.toList());
    }

    // ===================================================================
    // MODULE Operations
    // ===================================================================

    /**
     * Create a new module for a batch with optional sub-modules
     */
    public SyllabusModuleDTO createModule(Long batchId, CreateModuleRequest request) {
        log.info("Creating module '{}' for batch {}", request.getName(), batchId);

        Batch batch = requireBatch(batchId);

        // Check if display order already exists
        if (moduleRepository.existsByBatchIdAndDisplayOrder(batchId, request.getDisplayOrder())) {
            throw new BusinessRuleException("A module with display order " + request.getDisplayOrder() + " already exists");
        }

        SyllabusModule module = SyllabusModule.builder()
                .batch(batch)
                .collegeId(batch.getCollege().getId())
                .name(request.getName())
                .description(request.getDescription())
                .displayOrder(request.getDisplayOrder())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .submodules(new ArrayList<>())
                .build();

        // Add sub-modules if provided
        if (request.getSubmodules() != null && !request.getSubmodules().isEmpty()) {
            for (CreateSubmoduleRequest submoduleRequest : request.getSubmodules()) {
                SyllabusSubmodule submodule = buildSubmodule(module, submoduleRequest);
                module.addSubmodule(submodule);
            }
        }

        SyllabusModule savedModule = moduleRepository.save(module);
        log.info("Created module {} with {} sub-modules", savedModule.getId(), savedModule.getSubmodules().size());

        return convertToModuleDTO(savedModule);
    }

    /**
     * Update a module
     */
    public SyllabusModuleDTO updateModule(Long moduleId, UpdateModuleRequest request) {
        log.info("Updating module {}", moduleId);

        SyllabusModule module = requireModule(moduleId);

        if (request.getName() != null) {
            module.setName(request.getName());
        }
        if (request.getDescription() != null) {
            module.setDescription(request.getDescription());
        }
        if (request.getDisplayOrder() != null) {
            // Check for conflicts
            if (!module.getDisplayOrder().equals(request.getDisplayOrder()) &&
                    moduleRepository.existsByBatchIdAndDisplayOrder(module.getBatch().getId(),
                            request.getDisplayOrder())) {
                throw new BusinessRuleException("A module with display order " + request.getDisplayOrder() + " already exists");
            }
            module.setDisplayOrder(request.getDisplayOrder());
        }
        if (request.getStartDate() != null) {
            module.setStartDate(request.getStartDate());
        }
        if (request.getEndDate() != null) {
            module.setEndDate(request.getEndDate());
        }

        SyllabusModule updatedModule = moduleRepository.save(module);
        log.info("Updated module {}", moduleId);

        return convertToModuleDTO(updatedModule);
    }

    /**
     * Delete a module (will cascade delete all sub-modules and topics)
     */
    public void deleteModule(Long moduleId) {
        log.info("Deleting module {}", moduleId);

        if (!moduleRepository.existsById(moduleId)) {
            throw new ResourceNotFoundException("Module not found with id: " + moduleId);
        }

        moduleRepository.deleteById(moduleId);
        log.info("Deleted module {}", moduleId);
    }

    // ===================================================================
    // SUB-MODULE Operations
    // ===================================================================

    /**
     * Create a sub-module under a module
     */
    public SyllabusSubmoduleDTO createSubmodule(Long moduleId, CreateSubmoduleRequest request) {
        log.info("Creating sub-module '{}' for module {}", request.getName(), moduleId);

        SyllabusModule module = requireModule(moduleId);

        // Check if display order already exists
        if (submoduleRepository.existsByModuleIdAndDisplayOrder(moduleId, request.getDisplayOrder())) {
            throw new BusinessRuleException("A sub-module with display order " + request.getDisplayOrder() + " already exists");
        }

        SyllabusSubmodule submodule = buildSubmodule(module, request);
        SyllabusSubmodule savedSubmodule = submoduleRepository.save(submodule);

        log.info("Created sub-module {} with {} topics", savedSubmodule.getId(), savedSubmodule.getTopics().size());

        return convertToSubmoduleDTO(savedSubmodule);
    }

    /**
     * Update a sub-module
     */
    public SyllabusSubmoduleDTO updateSubmodule(Long submoduleId, UpdateSubmoduleRequest request) {
        log.info("Updating sub-module {}", submoduleId);

        SyllabusSubmodule submodule = requireSubmodule(submoduleId);

        if (request.getName() != null) {
            submodule.setName(request.getName());
        }
        if (request.getDescription() != null) {
            submodule.setDescription(request.getDescription());
        }
        if (request.getDisplayOrder() != null) {
            // Check for conflicts
            if (!submodule.getDisplayOrder().equals(request.getDisplayOrder()) &&
                    submoduleRepository.existsByModuleIdAndDisplayOrder(submodule.getModule().getId(),
                            request.getDisplayOrder())) {
                throw new BusinessRuleException("A sub-module with display order " + request.getDisplayOrder() + " already exists");
            }
            submodule.setDisplayOrder(request.getDisplayOrder());
        }
        if (request.getStartDate() != null) {
            submodule.setStartDate(request.getStartDate());
        }
        if (request.getEndDate() != null) {
            submodule.setEndDate(request.getEndDate());
        }
        if (request.getWeekNumber() != null) {
            submodule.setWeekNumber(request.getWeekNumber());
        }

        SyllabusSubmodule updatedSubmodule = submoduleRepository.save(submodule);
        log.info("Updated sub-module {}", submoduleId);

        return convertToSubmoduleDTO(updatedSubmodule);
    }

    /**
     * Delete a sub-module (will cascade delete all topics)
     */
    public void deleteSubmodule(Long submoduleId) {
        log.info("Deleting sub-module {}", submoduleId);

        if (!submoduleRepository.existsById(submoduleId)) {
            throw new ResourceNotFoundException("Sub-module not found with id: " + submoduleId);
        }

        submoduleRepository.deleteById(submoduleId);
        log.info("Deleted sub-module {}", submoduleId);
    }

    // ===================================================================
    // TOPIC Operations
    // ===================================================================

    /**
     * Add a topic to a sub-module
     */
    public SyllabusTopicDTO addTopicToSubmodule(Long submoduleId, CreateTopicRequest request) {
        log.info("Adding topic '{}' to sub-module {}", request.getName(), submoduleId);

        SyllabusSubmodule submodule = requireSubmodule(submoduleId);

        SyllabusTopic topic = SyllabusTopic.builder()
                .submodule(submodule)
                .name(request.getName())
                .description(request.getDescription())
                .displayOrder(request.getDisplayOrder())
                .isCompleted(false)
                .build();

        SyllabusTopic savedTopic = topicRepository.save(topic);
        log.info("Added topic {} to sub-module {}", savedTopic.getId(), submoduleId);

        return convertToTopicDTO(savedTopic);
    }

    /**
     * Update a topic
     */
    public SyllabusTopicDTO updateTopic(Long topicId, UpdateTopicRequest request) {
        log.info("Updating topic {}", topicId);

        SyllabusTopic topic = requireTopic(topicId);

        if (request.getName() != null) {
            topic.setName(request.getName());
        }
        if (request.getDescription() != null) {
            topic.setDescription(request.getDescription());
        }
        if (request.getDisplayOrder() != null) {
            topic.setDisplayOrder(request.getDisplayOrder());
        }

        SyllabusTopic updatedTopic = topicRepository.save(topic);
        log.info("Updated topic {}", topicId);

        return convertToTopicDTO(updatedTopic);
    }

    /**
     * Delete a topic
     */
    public void deleteTopic(Long topicId) {
        log.info("Deleting topic {}", topicId);

        if (!topicRepository.existsById(topicId)) {
            throw new ResourceNotFoundException("Topic not found with id: " + topicId);
        }

        topicRepository.deleteById(topicId);
        log.info("Deleted topic {}", topicId);
    }

    /**
     * Toggle topic completion status
     */
    public SyllabusTopicDTO toggleTopicCompletion(Long topicId) {
        log.info("Toggling completion for topic {}", topicId);

        SyllabusTopic topic = requireTopic(topicId);

        topic.toggleCompletion();
        SyllabusTopic updatedTopic = topicRepository.save(topic);

        log.info("Toggled topic {} completion to: {}", topicId, updatedTopic.getIsCompleted());

        return convertToTopicDTO(updatedTopic);
    }

    // ===================================================================
    // HELPER Methods
    // ===================================================================

    private SyllabusSubmodule buildSubmodule(SyllabusModule module, CreateSubmoduleRequest request) {
        SyllabusSubmodule submodule = SyllabusSubmodule.builder()
                .module(module)
                .name(request.getName())
                .description(request.getDescription())
                .displayOrder(request.getDisplayOrder())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .weekNumber(request.getWeekNumber())
                .topics(new ArrayList<>())
                .build();

        // Add topics if provided
        if (request.getTopics() != null && !request.getTopics().isEmpty()) {
            for (CreateTopicRequest topicRequest : request.getTopics()) {
                SyllabusTopic topic = SyllabusTopic.builder()
                        .submodule(submodule)
                        .name(topicRequest.getName())
                        .description(topicRequest.getDescription())
                        .displayOrder(topicRequest.getDisplayOrder())
                        .isCompleted(false)
                        .build();
                submodule.addTopic(topic);
            }
        }

        return submodule;
    }

    // ===================================================================
    // DTO Converters
    // ===================================================================

    private SyllabusModuleDTO convertToModuleDTO(SyllabusModule module) {
        List<SyllabusSubmoduleDTO> submoduleDTOs = module.getSubmodules() != null
                ? module.getSubmodules().stream()
                        .map(this::convertToSubmoduleDTO)
                        .collect(Collectors.toList())
                : new ArrayList<>();

        int totalTopics = submoduleDTOs.stream()
                .mapToInt(SyllabusSubmoduleDTO::getTopicsCount)
                .sum();

        int completedTopics = submoduleDTOs.stream()
                .mapToInt(SyllabusSubmoduleDTO::getCompletedTopicsCount)
                .sum();

        return SyllabusModuleDTO.builder()
                .id(module.getId())
                .name(module.getName())
                .description(module.getDescription())
                .displayOrder(module.getDisplayOrder())
                .startDate(module.getStartDate())
                .endDate(module.getEndDate())
                .submodules(submoduleDTOs)
                .submodulesCount(submoduleDTOs.size())
                .totalTopicsCount(totalTopics)
                .completedTopicsCount(completedTopics)
                .build();
    }

    private SyllabusSubmoduleDTO convertToSubmoduleDTO(SyllabusSubmodule submodule) {
        List<SyllabusTopicDTO> topicDTOs = submodule.getTopics() != null
                ? submodule.getTopics().stream()
                        .map(this::convertToTopicDTO)
                        .collect(Collectors.toList())
                : new ArrayList<>();

        int completedCount = (int) topicDTOs.stream()
                .filter(SyllabusTopicDTO::getIsCompleted)
                .count();

        return SyllabusSubmoduleDTO.builder()
                .id(submodule.getId())
                .name(submodule.getName())
                .description(submodule.getDescription())
                .displayOrder(submodule.getDisplayOrder())
                .startDate(submodule.getStartDate())
                .endDate(submodule.getEndDate())
                .weekNumber(submodule.getWeekNumber())
                .topics(topicDTOs)
                .topicsCount(topicDTOs.size())
                .completedTopicsCount(completedCount)
                .build();
    }

    private SyllabusTopicDTO convertToTopicDTO(SyllabusTopic topic) {
        return SyllabusTopicDTO.builder()
                .id(topic.getId())
                .name(topic.getName())
                .description(topic.getDescription())
                .displayOrder(topic.getDisplayOrder())
                .isCompleted(topic.getIsCompleted())
                .completedAt(topic.getCompletedAt())
                .build();
    }


    /**
     * Copies an entire curriculum tree from one batch to another.
     *
     * <p>Both batches are tenant-checked, so this cannot be used to lift another
     * college's curriculum -- which is the obvious abuse of a "copy from batch"
     * feature and the reason the source id is validated as carefully as the
     * target.
     *
     * <p>Refuses a non-empty target rather than merging. Merging raises questions
     * this feature does not answer -- what happens to display orders, whether a
     * same-named module is the same module -- and silently interleaving two
     * curricula is far worse than declining.
     *
     * <p>Topic completion flags are deliberately not copied: they describe what
     * the source batch's trainer has taught, which says nothing about the new
     * batch. Dates are copied, since they are usually adjusted afterwards
     * anyway and having them present is more useful than nulls.
     */
    @Transactional
    public List<SyllabusModuleDTO> copyCurriculum(Long targetBatchId, Long sourceBatchId) {
        if (targetBatchId.equals(sourceBatchId)) {
            throw new BusinessRuleException("Cannot copy a batch's curriculum onto itself");
        }

        Batch target = requireBatch(targetBatchId);
        requireBatch(sourceBatchId);

        if (!moduleRepository.findByBatchIdOrderByDisplayOrder(targetBatchId).isEmpty()) {
            throw new ConflictException("CURRICULUM_NOT_EMPTY",
                    "This batch already has a curriculum. Remove it before copying another.");
        }

        List<SyllabusModule> source = moduleRepository.findByBatchIdWithSubmodulesAndTopics(sourceBatchId);
        if (source.isEmpty()) {
            throw new BusinessRuleException("The source batch has no curriculum to copy");
        }

        for (SyllabusModule sourceModule : source) {
            SyllabusModule module = moduleRepository.save(SyllabusModule.builder()
                    .batch(target)
                    .collegeId(target.getCollege().getId())
                    .name(sourceModule.getName())
                    .description(sourceModule.getDescription())
                    .displayOrder(sourceModule.getDisplayOrder())
                    .startDate(sourceModule.getStartDate())
                    .endDate(sourceModule.getEndDate())
                    .build());

            for (SyllabusSubmodule sourceSub : sourceModule.getSubmodules()) {
                SyllabusSubmodule submodule = submoduleRepository.save(SyllabusSubmodule.builder()
                        .module(module)
                        .name(sourceSub.getName())
                        .description(sourceSub.getDescription())
                        .displayOrder(sourceSub.getDisplayOrder())
                        .startDate(sourceSub.getStartDate())
                        .endDate(sourceSub.getEndDate())
                        .build());

                for (SyllabusTopic sourceTopic : sourceSub.getTopics()) {
                    topicRepository.save(SyllabusTopic.builder()
                            .submodule(submodule)
                            .name(sourceTopic.getName())
                            .description(sourceTopic.getDescription())
                            .displayOrder(sourceTopic.getDisplayOrder())
                            // isCompleted / completedAt intentionally left at their
                            // defaults -- see the javadoc.
                            .build());
                }
            }
        }

        log.info("Copied curriculum from batch {} to batch {}", sourceBatchId, targetBatchId);
        return getCurriculumByBatchId(targetBatchId);
    }

    // ===================================================================
    // Tenant-guarded lookups
    // ===================================================================
    //
    // Every method here takes an id straight from the URL and this service had
    // no tenancy check of any kind, so a trainer at one college could read and
    // edit another college's curriculum by guessing ids. The Hibernate
    // collegeFilter does not help: it applies to queries, not to findById.
    //
    // Ownership failure and a genuine miss both raise the same 404, so ids
    // cannot be enumerated. The college is reached through the batch, which is
    // the only place it is stored on this tree.

    private Batch requireBatch(Long batchId) {
        return batchRepository.findById(batchId)
                .filter(b -> TenantGuard.isVisible(b.getCollege().getId()))
                .orElseThrow(() -> ResourceNotFoundException.of("Batch", batchId));
    }

    private SyllabusModule requireModule(Long moduleId) {
        return moduleRepository.findById(moduleId)
                .filter(m -> TenantGuard.isVisible(m.getBatch().getCollege().getId()))
                .orElseThrow(() -> ResourceNotFoundException.of("Module", moduleId));
    }

    private SyllabusSubmodule requireSubmodule(Long submoduleId) {
        return submoduleRepository.findById(submoduleId)
                .filter(sm -> TenantGuard.isVisible(sm.getModule().getBatch().getCollege().getId()))
                .orElseThrow(() -> ResourceNotFoundException.of("Sub-module", submoduleId));
    }

    private SyllabusTopic requireTopic(Long topicId) {
        return topicRepository.findById(topicId)
                .filter(t -> TenantGuard.isVisible(
                        t.getSubmodule().getModule().getBatch().getCollege().getId()))
                .orElseThrow(() -> ResourceNotFoundException.of("Topic", topicId));
    }
}
