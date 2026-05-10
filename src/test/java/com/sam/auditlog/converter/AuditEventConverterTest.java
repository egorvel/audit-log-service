package com.sam.auditlog.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sam.auditlog.dto.ActorRef;
import com.sam.auditlog.dto.CreateAuditEventRequest;
import com.sam.auditlog.dto.ResourceRef;
import com.sam.auditlog.model.AuditEvent;
import com.sam.auditlog.model.Outcome;

/** Pure unit test - no Spring context. */
class AuditEventConverterTest {

    private final AuditEventConverter converter = new AuditEventConverter();

    @Test
    void toEntity_assignsProvidedIdAndDropsTimestamp_andDefaultsContext() {
        var request =
                new CreateAuditEventRequest(
                        new ActorRef("u_42", "user"),
                        "user.login",
                        new ResourceRef("session_abc", "session"),
                        Outcome.SUCCESS,
                        null);

        AuditEvent entity = converter.toEntity(request, "01HE3XJ7N2K9V0R1B6T8Q4WMZ9");

        assertThat(entity.id()).isEqualTo("01HE3XJ7N2K9V0R1B6T8Q4WMZ9");
        assertThat(entity.timestamp()).isNull();
        assertThat(entity.actorId()).isEqualTo("u_42");
        assertThat(entity.actorType()).isEqualTo("user");
        assertThat(entity.resourceId()).isEqualTo("session_abc");
        assertThat(entity.resourceType()).isEqualTo("session");
        assertThat(entity.action()).isEqualTo("user.login");
        assertThat(entity.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(entity.context()).isEmpty();
    }

    @Test
    void toEntity_preservesContext() {
        var request =
                new CreateAuditEventRequest(
                        new ActorRef("svc_billing", "service"),
                        "invoice.paid",
                        new ResourceRef("invoice_777", "invoice"),
                        Outcome.SUCCESS,
                        Map.of("amount", 1200, "currency", "USD"));

        AuditEvent entity = converter.toEntity(request, "01HE3XJ7N2K9V0R1B6T8Q4WMZ9");

        assertThat(entity.context()).containsEntry("amount", 1200).containsEntry("currency", "USD");
    }

    @Test
    void toResponse_roundTripsAllFields() {
        var entity =
                new AuditEvent(
                        "01HE3XJ7N2K9V0R1B6T8Q4WMZ9",
                        Instant.parse("2026-04-25T10:00:00Z"),
                        "u_1",
                        "user",
                        "project_42",
                        "project",
                        "resource.updated",
                        Outcome.SUCCESS,
                        Map.of("k", "v"));

        var response = converter.toResponse(entity);

        assertThat(response.id()).isEqualTo("01HE3XJ7N2K9V0R1B6T8Q4WMZ9");
        assertThat(response.timestamp()).isEqualTo(Instant.parse("2026-04-25T10:00:00Z"));
        assertThat(response.actor()).isEqualTo(new ActorRef("u_1", "user"));
        assertThat(response.resource()).isEqualTo(new ResourceRef("project_42", "project"));
        assertThat(response.action()).isEqualTo("resource.updated");
        assertThat(response.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(response.context()).containsEntry("k", "v");
    }

    @Test
    void auditEvent_actorIdIsRequired() {
        assertThatThrownBy(
                        () ->
                                new AuditEvent(
                                        "01HE3XJ7N2K9V0R1B6T8Q4WMZ9",
                                        null,
                                        null,
                                        "user",
                                        "r",
                                        "type",
                                        "act",
                                        Outcome.SUCCESS,
                                        Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actor.id");
    }

    @Test
    void auditEvent_isImmutable_contextIsCopiedAndUnmodifiable() {
        var mutable = new java.util.HashMap<String, Object>();
        mutable.put("a", 1);
        var entity =
                new AuditEvent(
                        "01HE3XJ7N2K9V0R1B6T8Q4WMZ9",
                        null,
                        "u_1",
                        "user",
                        "r_1",
                        "thing",
                        "act",
                        Outcome.SUCCESS,
                        mutable);

        mutable.put("b", 2);

        assertThat(entity.context()).hasSize(1).containsEntry("a", 1);
        assertThatThrownBy(() -> entity.context().put("c", 3))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
