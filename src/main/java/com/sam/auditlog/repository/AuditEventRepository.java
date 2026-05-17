package com.sam.auditlog.repository;

import java.time.Instant;
import java.util.Collection;
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
     * <p>The {@code actors} parameter accepts a {@link Collection} of one or more distinct ids
     * (requirements §AC1.2 / §AC1.11). Passing {@code null} disables the actor filter; passing an
     * empty collection is the caller's mistake — the service rejects empty input upstream with
     * {@link com.sam.auditlog.service.EmptyFilterException} so the JPQL never has to defend
     * against the JPA "empty IN list" portability hazard. Postgres serves the {@code IN} list via
     * a {@code MergeAppend} over per-actor scans against {@code idx_events_actor_ts_id} (design
     * §4), so no extra index is needed.
     *
     * <p>The caller passes a {@code Pageable.ofSize(limit + 1)} so the service layer can detect
     * "more rows exist" without an extra count query.
     */
    @Query(
            value =
                    """
SELECT e FROM AuditEvent e
 WHERE (:actors                  IS NULL OR e.actorId IN :actors)
   AND (cast(:resource as string)  IS NULL OR e.resourceId = :resource)
   AND (cast(:from as Instant)     IS NULL OR e.timestamp >= :from)
   AND (cast(:to as Instant)       IS NULL OR e.timestamp <  :to)
   AND (cast(:cursorTs as Instant) IS NULL OR e.timestamp <  :cursorTs
                                          OR (e.timestamp = :cursorTs AND e.id < :cursorId))
 ORDER BY e.timestamp DESC, e.id DESC
""")
    List<AuditEvent> findPage(
            @Param("actors") Collection<String> actors,
            @Param("resource") String resource,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("cursorTs") Instant cursorTs,
            @Param("cursorId") String cursorId,
            Pageable pageable);
}
