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

        Batch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new RuntimeException("Batch not found with id: " + batchId));

        // Check if display order already exists
        if (moduleRepository.existsByBatchIdAndDisplayOrder(batchId, request.getDisplayOrder())) {
            throw new RuntimeException("A module with display order " + request.getDisplayOrder() + " already exists");
        }

        SyllabusModule module = SyllabusModule.builder()
                .batch(batch)
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

        SyllabusModule module = moduleRepository.findById(moduleId)
                .orElseThrow(() -> new RuntimeException("Module not found with id: " + moduleId));

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
                throw new RuntimeException(
                        "A module with display order " + request.getDisplayOrder() + " already exists");
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
            throw new RuntimeException("Module not found with id: " + moduleId);
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

        SyllabusModule module = moduleRepository.findById(moduleId)
                .orElseThrow(() -> new RuntimeException("Module not found with id: " + moduleId));

        // Check if display order already exists
        if (submoduleRepository.existsByModuleIdAndDisplayOrder(moduleId, request.getDisplayOrder())) {
            throw new RuntimeException(
                    "A sub-module with display order " + request.getDisplayOrder() + " already exists");
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

        SyllabusSubmodule submodule = submoduleRepository.findById(submoduleId)
                .orElseThrow(() -> new RuntimeException("Sub-module not found with id: " + submoduleId));

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
                throw new RuntimeException(
                        "A sub-module with display order " + request.getDisplayOrder() + " already exists");
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
            throw new RuntimeException("Sub-module not found with id: " + submoduleId);
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

        SyllabusSubmodule submodule = submoduleRepository.findById(submoduleId)
                .orElseThrow(() -> new RuntimeException("Sub-module not found with id: " + submoduleId));

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

        SyllabusTopic topic = topicRepository.findById(topicId)
                .orElseThrow(() -> new RuntimeException("Topic not found with id: " + topicId));

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
            throw new RuntimeException("Topic not found with id: " + topicId);
        }

        topicRepository.deleteById(topicId);
        log.info("Deleted topic {}", topicId);
    }

    /**
     * Toggle topic completion status
     */
    public SyllabusTopicDTO toggleTopicCompletion(Long topicId) {
        log.info("Toggling completion for topic {}", topicId);

        SyllabusTopic topic = topicRepository.findById(topicId)
                .orElseThrow(() -> new RuntimeException("Topic not found with id: " + topicId));

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
}
