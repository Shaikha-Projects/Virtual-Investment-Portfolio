package com.ga.authservice.Repository;

import com.ga.authservice.Model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    //method to retrieve unpublished events
    List<OutboxEvent> findByPublishedFalseOrderByIdAsc();
}
