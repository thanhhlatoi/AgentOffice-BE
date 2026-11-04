package com.agentoffice.mapper.task;

import com.agentoffice.dto.task.GoalDto;
import com.agentoffice.entity.task.Goal;
import org.mapstruct.Mapper;

/** Không đưa `plan` (JSON lớn) vào GoalDto: GoalDetailDto mang nó riêng. */
@Mapper
public interface GoalMapper {
    GoalDto toDto(Goal g);
}
