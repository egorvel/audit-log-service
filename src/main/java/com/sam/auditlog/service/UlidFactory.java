package com.sam.auditlog.service;

import java.time.Instant;

import org.springframework.stereotype.Component;

import com.github.f4b6a3.ulid.UlidCreator;

/**
 * App-side ULID generator. {@link #next()} is monotonic per JVM (used at runtime); {@link
 * #fromTimestamp(Instant)} seeds the time component from the given instant (used by the V2 backfill
 * so historical rows retain their timestamp ordering).
 */
@Component
public class UlidFactory {

    public String next() {
        return UlidCreator.getMonotonicUlid().toString();
    }

    public String fromTimestamp(Instant instant) {
        return UlidCreator.getUlid(instant.toEpochMilli()).toString();
    }
}
