package com.sam.auditlog.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.f4b6a3.ulid.Ulid;

/** Pure unit test - no Spring context. */
class UlidFactoryTest {

    private final UlidFactory factory = new UlidFactory();

    @Test
    void next_returns26CharCrockfordBase32() {
        String value = factory.next();

        assertThat(value).hasSize(26);
        assertThat(Ulid.from(value)).isNotNull();
    }

    @Test
    void next_isMonotonicAcrossSameMillisecondCalls() {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            values.add(factory.next());
        }

        for (int i = 1; i < values.size(); i++) {
            assertThat(values.get(i))
                    .as("ULID %d should be > ULID %d", i, i - 1)
                    .isGreaterThan(values.get(i - 1));
        }
    }

    @Test
    void fromTimestamp_seedsTimeComponentExactly() {
        Instant instant = Instant.parse("2026-04-25T10:00:00.123Z");

        String value = factory.fromTimestamp(instant);

        assertThat(value).hasSize(26);
        Ulid parsed = Ulid.from(value);
        assertThat(parsed.getTime()).isEqualTo(instant.toEpochMilli());
    }

    @Test
    void fromTimestamp_preservesTimestampOrderInLexSort() {
        Instant earlier = Instant.parse("2026-04-25T10:00:00.000Z");
        Instant later = Instant.parse("2026-04-25T10:00:00.500Z");

        for (int i = 0; i < 50; i++) {
            String earlierUlid = factory.fromTimestamp(earlier);
            String laterUlid = factory.fromTimestamp(later);
            assertThat(laterUlid)
                    .as("ULID seeded at later instant must lex-sort after earlier one")
                    .isGreaterThan(earlierUlid);
        }
    }
}
