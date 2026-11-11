package com.agentoffice.repository.event;

import com.agentoffice.entity.event.DomainEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DomainEventRepository extends JpaRepository<DomainEvent, Long> {
    @org.springframework.data.jpa.repository.Query("select coalesce(max(e.id), 0) from DomainEvent e")
    long maxId();

    List<DomainEvent> findTop200ByIdGreaterThanOrderByIdAsc(long afterId);

    List<DomainEvent> findByTypeOrderByIdAsc(String type);

    List<DomainEvent> findTop200ByTypeAndAggregateIdOrderByIdDesc(String type, java.util.UUID aggregateId);
}
