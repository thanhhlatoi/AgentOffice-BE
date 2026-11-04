package com.agentoffice.service.event;

import com.agentoffice.entity.event.DomainEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentoffice.support.PostgresIT;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class OutboxIT extends PostgresIT {
    @Autowired EventPublisher publisher;
    @Autowired PlatformTransactionManager txm;

    @Test void commitKeepsTheEventWithOccurredAt() {
        UUID agg = uuid();
        new TransactionTemplate(txm).executeWithoutResult(s -> publisher.publish("x.happened", agg, Map.of("a", 1)));
        DomainEvent e = events().stream().filter(x -> agg.equals(x.getAggregateId())).findFirst().orElseThrow();
        assertThat(e.getPayload()).contains("\"a\"").contains("occurredAt");
    }

    @Test void rollbackLeavesNoEvent() {
        UUID agg = uuid();
        assertThatThrownBy(() -> new TransactionTemplate(txm).executeWithoutResult(s -> {
            publisher.publish("x.happened", agg, Map.of());
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(events()).noneMatch(x -> agg.equals(x.getAggregateId()));
    }

    @Test void publishingOutsideATransactionFails() {
        assertThatThrownBy(() -> publisher.publish("x.happened", uuid(), Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test void idsAreStrictlyIncreasingInPublishOrder() {
        new TransactionTemplate(txm).executeWithoutResult(s -> {
            for (int i = 0; i < 5; i++) publisher.publish("n." + i, null, Map.of());
        });
        assertThat(events()).extracting(DomainEvent::getId).isSorted().doesNotHaveDuplicates();
        assertThat(eventTypes()).containsExactly("n.0", "n.1", "n.2", "n.3", "n.4");
    }
}
