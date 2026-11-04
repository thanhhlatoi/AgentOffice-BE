package com.agentoffice.mapper.task;

import com.agentoffice.dto.task.TaskDto;
import com.agentoffice.entity.task.Task;
import org.mapstruct.Mapper;

@Mapper
public interface TaskMapper {
    TaskDto toDto(Task t);
}
