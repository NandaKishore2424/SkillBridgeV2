package com.skillbridge.syllabus.service;

import com.skillbridge.batch.entity.Batch;
import com.skillbridge.batch.repository.BatchRepository;
import com.skillbridge.syllabus.dto.*;
import com.skillbridge.syllabus.entity.SyllabusModule;
import com.skillbridge.syllabus.entity.SyllabusTopic;
import com.skillbridge.syllabus.repository.SyllabusModuleRepository;
import com.skillbridge.syllabus.repository.SyllabusTopicRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for managing batch syllabus (modules and topics)
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class SyllabusService {

    private final SyllabusModuleRepository moduleRepository;
    private final SyllabusTopicRepository topicRepository;
    private final BatchRepository batchRepository;

    /**
     * Get complete syllabus for a batch
     */
    @Transactional(readOnly = true)
    public List<SyllabusModuleDTO> getSyllabusByBatchId(Long batchId) {
        log.info("Fetching syllabus for batch {}", batchId);

        List<SyllabusModule> modules = moduleRepository.findByBatchIdWithTopics(batchId);

        return modules.stream()
                .map(this::convertToModuleDTO)
                .collect(Collectors.toList());
    }

    /**
     * Create a new module for a batch
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
                .topics(new ArrayList<>())
                .build();

        // Add topics if provided
        if (request.getTopics() != null && !request.getTopics().isEmpty()) {
            for (CreateTopicRequest topicRequest : request.getTopics()) {
                SyllabusTopic topic = SyllabusTopic.builder()
                        .module(module)
                        .name(topicRequest.getName())
                        .description(topicRequest.getDescription())
                        .displayOrder(topicRequest.getDisplayOrder())
                        .isCompleted(false)
                        .build();
                module.addTopic(topic);
            }
        }

        SyllabusModule savedModule = moduleRepository.save(module);
        log.info("Created module {} with {} topics", savedModule.getId(), savedModule.getTopics().size());

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
            // Check if new display order conflicts
            if (!request.getDisplayOrder().equals(module.getDisplayOrder())) {
                if (moduleRepository.existsByBatchIdAndDisplayOrder(
                        module.getBatch().getId(), request.getDisplayOrder())) {
                    throw new RuntimeException(
                            "A module with display order " + request.getDisplayOrder() + " already exists");
                }
                module.setDisplayOrder(request.getDisplayOrder());
            }
        }

        SyllabusModule updatedModule = moduleRepository.save(module);
        return convertToModuleDTO(updatedModule);
    }

    /**
     * Delete a module (will cascade delete all topics)
     */
    public void deleteModule(Long moduleId) {
        log.info("Deleting module {}", moduleId);

        if (!moduleRepository.existsById(moduleId)) {
            throw new RuntimeException("Module not found with id: " + moduleId);
        }

        moduleRepository.deleteById(moduleId);
        log.info("Deleted module {}", moduleId);
    }

    /**
     * Add a topic to a module
     */
    public SyllabusTopicDTO addTopicToModule(Long moduleId, CreateTopicRequest request) {
        log.info("Adding topic '{}' to module {}", request.getName(), moduleId);

        SyllabusModule module = moduleRepository.findById(moduleId)
                .orElseThrow(() -> new RuntimeException("Module not found with id: " + moduleId));

        // Check if display order already exists in this module
        if (topicRepository.existsByModuleIdAndDisplayOrder(moduleId, request.getDisplayOrder())) {
            throw new RuntimeException(
                    "A topic with display order " + request.getDisplayOrder() + " already exists in this module");
        }

        SyllabusTopic topic = SyllabusTopic.builder()
                .module(module)
                .name(request.getName())
                .description(request.getDescription())
                .displayOrder(request.getDisplayOrder())
                .isCompleted(false)
                .build();

        SyllabusTopic savedTopic = topicRepository.save(topic);
        log.info("Created topic {}", savedTopic.getId());

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
            // Check if new display order conflicts
            if (!request.getDisplayOrder().equals(topic.getDisplayOrder())) {
                if (topicRepository.existsByModuleIdAndDisplayOrder(
                        topic.getModule().getId(), request.getDisplayOrder())) {
                    throw new RuntimeException(
                            "A topic with display order " + request.getDisplayOrder() + " already exists");
                }
                topic.setDisplayOrder(request.getDisplayOrder());
            }
        }

        SyllabusTopic updatedTopic = topicRepository.save(topic);
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
        log.info("Toggling completion status for topic {}", topicId);

        SyllabusTopic topic = topicRepository.findById(topicId)
                .orElseThrow(() -> new RuntimeException("Topic not found with id: " + topicId));

        topic.toggleCompletion();
        SyllabusTopic updatedTopic = topicRepository.save(topic);

        log.info("Topic {} is now {}", topicId, updatedTopic.getIsCompleted() ? "completed" : "not completed");
        return convertToTopicDTO(updatedTopic);
    }

    /**
     * Copy syllabus from one batch to another
     */
    public List<SyllabusModuleDTO> copySyllabusFromBatch(Long sourceBatchId, Long targetBatchId) {
        log.info("Copying syllabus from batch {} to batch {}", sourceBatchId, targetBatchId);

        Batch targetBatch = batchRepository.findById(targetBatchId)
                .orElseThrow(() -> new RuntimeException("Target batch not found with id: " + targetBatchId));

        // Get source syllabus
        List<SyllabusModule> sourceModules = moduleRepository.findByBatchIdWithTopics(sourceBatchId);

        if (sourceModules.isEmpty()) {
            throw new RuntimeException("Source batch has no syllabus to copy");
        }

        // Check if target already has syllabus
        long existingModules = moduleRepository.countByBatchId(targetBatchId);
        if (existingModules > 0) {
            throw new RuntimeException("Target batch already has a syllabus. Delete it first before copying.");
        }

        List<SyllabusModule> copiedModules = new ArrayList<>();

        for (SyllabusModule sourceModule : sourceModules) {
            SyllabusModule newModule = SyllabusModule.builder()
                    .batch(targetBatch)
                    .name(sourceModule.getName())
                    .description(sourceModule.getDescription())
                    .displayOrder(sourceModule.getDisplayOrder())
                    .topics(new ArrayList<>())
                    .build();

            // Copy topics
            for (SyllabusTopic sourceTopic : sourceModule.getTopics()) {
                SyllabusTopic newTopic = SyllabusTopic.builder()
                        .module(newModule)
                        .name(sourceTopic.getName())
                        .description(sourceTopic.getDescription())
                        .displayOrder(sourceTopic.getDisplayOrder())
                        .isCompleted(false) // Reset completion status
                        .build();
                newModule.addTopic(newTopic);
            }

            copiedModules.add(newModule);
        }

        List<SyllabusModule> savedModules = moduleRepository.saveAll(copiedModules);
        log.info("Copied {} modules with topics from batch {} to batch {}",
                savedModules.size(), sourceBatchId, targetBatchId);

        return savedModules.stream()
                .map(this::convertToModuleDTO)
                .collect(Collectors.toList());
    }

    /**
     * Convert Module entity to DTO
     */
    private SyllabusModuleDTO convertToModuleDTO(SyllabusModule module) {
        List<SyllabusTopicDTO> topicDTOs = module.getTopics().stream()
                .map(this::convertToTopicDTO)
                .collect(Collectors.toList());

        long completedCount = module.getTopics().stream()
                .filter(SyllabusTopic::getIsCompleted)
                .count();

        return SyllabusModuleDTO.builder()
                .id(module.getId())
                .name(module.getName())
                .description(module.getDescription())
                .displayOrder(module.getDisplayOrder())
                .topics(topicDTOs)
                .topicsCount(module.getTopics().size())
                .completedTopicsCount((int) completedCount)
                .build();
    }

    /**
     * Convert Topic entity to DTO
     */
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
