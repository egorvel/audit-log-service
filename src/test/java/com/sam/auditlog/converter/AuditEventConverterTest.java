package com.sam.auditlog.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sam.auditlog.dto.CreateAuditEventRequest;
import com.sam.auditlog.model.AuditEvent;
import com.sam.auditlog.model.Outcome;

/** Pure unit test - no Spring context. */
class AuditEventConverterTest {

    private final AuditEventConverter converter = new AuditEventConverter();

    @Test
    void toEntity_dropsTimestampAndId_andDefaultsContext() {
        var request =
                new CreateAuditEventRequest(
                        "user:42", "user.login", "session:abc", Outcome.SUCCESS, null);

        AuditEvent entity = converter.toEntity(request);

        assertThat(entity.id()).isNull();
        assertThat(entity.timestamp()).isNull();
        assertThat(entity.actor()).isEqualTo("user:42");
        assertThat(entity.action()).isEqualTo("user.login");
        assertThat(entity.resource()).isEqualTo("session:abc");
        assertThat(entity.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(entity.context()).isEmpty();
    }

    @Test
    void toEntity_preservesContext() {
        var request =
                new CreateAuditEventRequest(
                        "svc:billing",
                        "invoice.paid",
                        "invoice:777",
                        Outcome.SUCCESS,
                        Map.of("amount", 1200, "currency", "USD"));

        AuditEvent entity = converter.toEntity(request);

        assertThat(entity.context()).containsEntry("amount", 1200).containsEntry("currency", "USD");
    }

    @Test
    void toResponse_roundTripsAllFields() {
        var entity =
                new AuditEvent(
                        7L,
                        Instant.parse("2026-04-25T10:00:00Z"),
                        "user:1",
                        "resource.updated",
                        "project:42",
                        Outcome.SUCCESS,
                        Map.of("k", "v"));

        var response = converter.toResponse(entity);

        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.timestamp()).isEqualTo(Instant.parse("2026-04-25T10:00:00Z"));
        assertThat(response.actor()).isEqualTo("user:1");
        assertThat(response.action()).isEqualTo("resource.updated");
        assertThat(response.resource()).isEqualTo("project:42");
        assertThat(response.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(response.context()).containsEntry("k", "v");
    }

    @Test
    void auditEvent_actorIsRequired() {
        assertThatThrownBy(
                        () -> new AuditEvent(null, null, null, "x", "y", Outcome.SUCCESS, Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actor");
    }

    @Test
    void auditEvent_isImmutable_contextIsCopiedAndUnmodifiable() {
        var mutable = new java.util.HashMap<String, Object>();
        mutable.put("a", 1);
        var entity = new AuditEvent(null, null, "actor", "act", "res", Outcome.SUCCESS, mutable);

        mutable.put("b", 2);

        assertThat(entity.context()).hasSize(1).containsEntry("a", 1);
        assertThatThrownBy(() -> entity.context().put("c", 3))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
