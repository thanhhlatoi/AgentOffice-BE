package com.agentoffice.repository.event;

import com.agentoffice.entity.event.DomainEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DomainEventRepository extends JpaRepository<DomainEvent, Long> {
    List<DomainEvent> findTop200ByIdGreaterThanOrderByIdAsc(long afterId);
}
