package com.skillbridge.student.dto;

import com.skillbridge.student.entity.Skill;
import lombok.Builder;
import lombok.Data;

/**
 * Wire format for a skill in the reference catalogue.
 *
 * <p>Replaces returning the {@code Skill} entity. The catalogue is read by the
 * student profile screen to populate a picker, which needs an id, a label and a
 * grouping — {@code createdAt} was being sent purely because it was on the row.
 */
@Data
@Builder
public class SkillDTO {

    private Long id;
    private String name;
    private String category;

    public static SkillDTO from(Skill skill) {
        return SkillDTO.builder()
                .id(skill.getId())
                .name(skill.getName())
                .category(skill.getCategory())
                .build();
    }
}
