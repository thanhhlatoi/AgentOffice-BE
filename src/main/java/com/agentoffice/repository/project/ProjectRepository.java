package com.agentoffice.repository.project;

import com.agentoffice.domain.project.ProjectStatus;
import com.agentoffice.entity.project.Project;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface ProjectRepository extends JpaRepository<Project, UUID> {
    /** Khoá hàng dự án: hai task kết thúc cùng lúc tính lại tiến độ tuần tự, kẻ sau thấy kết quả của kẻ trước. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Project p where p.id = :id")
    Optional<Project> findByIdForUpdate(UUID id);

    List<Project> findByStatusInOrderByCreatedAtDesc(List<ProjectStatus> statuses);

    List<Project> findAllByOrderByCreatedAtDesc();
}
