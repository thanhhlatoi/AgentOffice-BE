package com.agentoffice.entity.task;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

@Embeddable
public record TaskDependencyId(UUID taskId, UUID dependsOnTaskId) implements Serializable {}
