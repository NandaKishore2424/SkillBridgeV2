package com.skillbridge.syllabus.service;

import com.skillbridge.syllabus.dto.SyllabusModuleDTO;
import com.skillbridge.syllabus.dto.SyllabusSubmoduleDTO;
import com.skillbridge.syllabus.dto.SyllabusTopicDTO;
import com.skillbridge.syllabus.entity.SyllabusModule;
import com.skillbridge.syllabus.entity.SyllabusSubmodule;
import com.skillbridge.syllabus.entity.SyllabusTopic;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Entity to wire format for the curriculum tree.
 *
 * <p>Static and stateless, and separate from {@link SyllabusService} because two
 * beans now need it: the service, whose writes return the shape they just
 * changed, and {@link CurriculumReader}, whose read is cached. Leaving it on the
 * service and copying it into the reader would be two mappers that agree until
 * one of them is edited.
 *
 * <p>Every method here reads collections the caller must already have
 * initialised. {@code open-in-view} is off, so a lazy association touched here
 * throws rather than quietly firing a query -- see {@code CurriculumReader} for
 * the two queries that make the whole tree safe to walk.
 */
final class SyllabusDtoMapper {

    private SyllabusDtoMapper() {
    }

    static SyllabusModuleDTO toModuleDto(SyllabusModule module) {
        List<SyllabusSubmoduleDTO> submoduleDTOs = module.getSubmodules() != null
                ? module.getSubmodules().stream()
                        .map(SyllabusDtoMapper::toSubmoduleDto)
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

    static SyllabusSubmoduleDTO toSubmoduleDto(SyllabusSubmodule submodule) {
        List<SyllabusTopicDTO> topicDTOs = submodule.getTopics() != null
                ? submodule.getTopics().stream()
                        .map(SyllabusDtoMapper::toTopicDto)
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

    static SyllabusTopicDTO toTopicDto(SyllabusTopic topic) {
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
