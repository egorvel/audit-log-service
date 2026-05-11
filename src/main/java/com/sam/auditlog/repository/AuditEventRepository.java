package com.sam.auditlog.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sam.auditlog.model.AuditEvent;

public interface AuditEventRepository extends JpaRepository<AuditEvent, String> {

    /**
     * Keyset-paginated query over {@code (timestamp DESC, id DESC)}. Each filter parameter is
     * null-tolerant (a {@code null} disables that filter). The cursor parameters {@code (cursorTs,
     * cursorId)} together encode the keyset position; they must be supplied together (caller's
     * responsibility) and either both null (first page) or both non-null.
     *
     * <p>The caller passes a {@code Pageable.ofSize(limit + 1)} so the service layer can detect
     * "more rows exist" without an extra count query.
     */
    @Query(
            value =
                    """
SELECT e FROM AuditEvent e
 WHERE (cast(:actor as string)     IS NULL OR e.actorId    = :actor)
   AND (cast(:resource as string)  IS NULL OR e.resourceId = :resource)
   AND (cast(:from as Instant)     IS NULL OR e.timestamp >= :from)
   AND (cast(:to as Instant)       IS NULL OR e.timestamp <  :to)
   AND (cast(:cursorTs as Instant) IS NULL OR e.timestamp <  :cursorTs
                                          OR (e.timestamp = :cursorTs AND e.id < :cursorId))
 ORDER BY e.timestamp DESC, e.id DESC
""")
    List<AuditEvent> findPage(
            @Param("actor") String actor,
            @Param("resource") String resource,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("cursorTs") Instant cursorTs,
            @Param("cursorId") String cursorId,
            Pageable pageable);
}
