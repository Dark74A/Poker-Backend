package com.example.backend.repositories;

import com.example.backend.projections.SessionProjection;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SessionProjectionRepository
        extends MongoRepository<SessionProjection, String> {

    Page<SessionProjection> findByHostIdOrPlayersUserId(String hostId, String userId, Pageable pageable);

    Page<SessionProjection> findByHostId(String hostId, Pageable pageable);

    Page<SessionProjection> findByPlayersUserIdAndHostIdNot(String userId, String hostId, Pageable pageable);

    Optional<SessionProjection> findByInviteCodeIgnoreCase(String inviteCode);

    boolean existsByInviteCode(String inviteCode);

}
