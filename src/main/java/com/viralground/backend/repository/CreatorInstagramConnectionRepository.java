package com.viralground.backend.repository;

import com.viralground.backend.entity.ConnectionStatus;
import com.viralground.backend.entity.CreatorInstagramConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CreatorInstagramConnectionRepository
        extends JpaRepository<CreatorInstagramConnection, Integer> {

    Optional<CreatorInstagramConnection> findByCreatorId(Integer creatorId);

    List<CreatorInstagramConnection> findByStatus(ConnectionStatus status);

    long countByStatus(ConnectionStatus status);
}
