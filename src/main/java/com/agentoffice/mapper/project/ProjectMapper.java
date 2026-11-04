package com.agentoffice.mapper.project;

import com.agentoffice.dto.project.ProjectDto;
import com.agentoffice.entity.project.Project;
import org.mapstruct.Mapper;

@Mapper
public interface ProjectMapper {
    ProjectDto.View toView(Project p);
}
