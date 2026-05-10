package com.sam.auditlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import com.sam.auditlog.dto.ActorRef;
import com.sam.auditlog.dto.CreateAuditEventRequest;
import com.sam.auditlog.dto.ResourceRef;
import com.sam.auditlog.model.Outcome;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuditEventIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.3-alpine")
                    .withDatabaseName("auditlog")
                    .withUsername("test")
                    .withPassword("test");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry reg) {
        reg.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        reg.add("spring.datasource.username", POSTGRES::getUsername);
        reg.add("spring.datasource.password", POSTGRES::getPassword);
        reg.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        reg.add("spring.flyway.user", POSTGRES::getUsername);
        reg.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void postEvent_persistsAndReturns201_withServerAssignedTimestampAndUlidId() throws Exception {
        var body =
                mapper.writeValueAsString(
                        new CreateAuditEventRequest(
                                new ActorRef("u_42", "user"),
                                "user.login",
                                new ResourceRef("session_abc", "session"),
                                Outcome.SUCCESS,
                                Map.of("ip", "10.0.0.1")));

        var result =
                mvc.perform(
                                post("/api/v1/audit-events")
                                        .contentType(APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andExpect(header().exists("Location"))
                        .andExpect(jsonPath("$.id").isString())
                        .andExpect(jsonPath("$.timestamp").exists())
                        .andExpect(jsonPath("$.actor.id").value("u_42"))
                        .andExpect(jsonPath("$.actor.type").value("user"))
                        .andExpect(jsonPath("$.resource.id").value("session_abc"))
                        .andExpect(jsonPath("$.resource.type").value("session"))
                        .andExpect(jsonPath("$.outcome").value("SUCCESS"))
                        .andReturn();

        JsonNode resp = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(resp.get("id").asText()).hasSize(26);
        assertThat(resp.get("context").get("ip").asText()).isEqualTo("10.0.0.1");
    }

    @Test
    void postEvent_ignoresClientSuppliedTimestamp() throws Exception {
        // Even if the JSON contains a timestamp field, the DTO does not bind it -
        // server-assigned timestamps are the only ones used.
        String body =
                """
                {
                  "timestamp": "1999-01-01T00:00:00Z",
                  "actor": {"id": "u_1", "type": "user"},
                  "action": "resource.read",
                  "resource": {"id": "doc_1", "type": "doc"},
                  "outcome": "SUCCESS"
                }
                """;

        var result =
                mvc.perform(
                                post("/api/v1/audit-events")
                                        .contentType(APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andReturn();

        JsonNode resp = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(resp.get("timestamp").asText()).doesNotStartWith("1999");
    }

    @Test
    void postEvent_rejectsMissingActor() throws Exception {
        String body =
                """
                {"action":"x","resource":{"id":"r","type":"t"},"outcome":"SUCCESS"}
                """;
        mvc.perform(post("/api/v1/audit-events").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void postEvent_rejectsBlankActorId() throws Exception {
        String body =
                """
                {"actor":{"id":"   ","type":"user"},"action":"x",
                 "resource":{"id":"r","type":"t"},"outcome":"SUCCESS"}
                """;
        mvc.perform(post("/api/v1/audit-events").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postEvent_rejectsMissingActorType() throws Exception {
        String body =
                """
                {"actor":{"id":"u_1"},"action":"x",
                 "resource":{"id":"r","type":"t"},"outcome":"SUCCESS"}
                """;
        mvc.perform(post("/api/v1/audit-events").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void database_blocksUpdate() {
        String ulid = UlidCreator.getMonotonicUlid().toString();
        jdbc.update(
                "INSERT INTO audit_events"
                        + " (id, actor_id, actor_type, action, resource_id, resource_type,"
                        + " outcome) VALUES (?, ?, ?, ?, ?, ?, ?)",
                ulid,
                "u_1",
                "user",
                "act",
                "r_1",
                "thing",
                "success");

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE audit_events SET actor_id = 'hacker' WHERE id = ?",
                                        ulid))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void database_blocksDelete() {
        String ulid = UlidCreator.getMonotonicUlid().toString();
        jdbc.update(
                "INSERT INTO audit_events"
                        + " (id, actor_id, actor_type, action, resource_id, resource_type,"
                        + " outcome) VALUES (?, ?, ?, ?, ?, ?, ?)",
                ulid,
                "u_2",
                "user",
                "act",
                "r_2",
                "thing",
                "success");

        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_events"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void database_blocksTruncate() {
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE audit_events"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void openApiJson_isExposed() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.paths['/api/v1/audit-events']").exists());
    }

    @Test
    void swaggerUi_isExposed() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
