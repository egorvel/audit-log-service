package com.sam.auditlog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sam.auditlog.dto.CreateAuditEventRequest;
import com.sam.auditlog.model.Outcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.dao.DataAccessException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuditEventIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.3-alpine")
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
    void postEvent_persistsAndReturns201_withServerAssignedTimestamp() throws Exception {
        var body = mapper.writeValueAsString(new CreateAuditEventRequest(
                "user:42", "user.login", "session:abc", Outcome.SUCCESS,
                Map.of("ip", "10.0.0.1")));

        var result = mvc.perform(post("/api/v1/events")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.actor").value("user:42"))
                .andExpect(jsonPath("$.outcome").value("SUCCESS"))
                .andReturn();

        JsonNode resp = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(resp.get("context").get("ip").asText()).isEqualTo("10.0.0.1");
    }

    @Test
    void postEvent_ignoresClientSuppliedTimestamp() throws Exception {
        // Even if the JSON contains a timestamp field, the DTO does not bind it -
        // server-assigned timestamps are the only ones used.
        String body = """
                {
                  "timestamp": "1999-01-01T00:00:00Z",
                  "actor": "user:1",
                  "action": "resource.read",
                  "resource": "doc:1",
                  "outcome": "SUCCESS"
                }
                """;

        var result = mvc.perform(post("/api/v1/events")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode resp = mapper.readTree(result.getResponse().getContentAsString());
        // Timestamp must NOT be the client-supplied 1999 value
        assertThat(resp.get("timestamp").asText()).doesNotStartWith("1999");
    }

    @Test
    void postEvent_rejectsMissingActor() throws Exception {
        String body = """
                {"action":"x","resource":"y","outcome":"SUCCESS"}
                """;
        mvc.perform(post("/api/v1/events")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void postEvent_rejectsBlankActor() throws Exception {
        String body = """
                {"actor":"   ","action":"x","resource":"y","outcome":"SUCCESS"}
                """;
        mvc.perform(post("/api/v1/events")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getEvents_returnsRecent() throws Exception {
        var body = mapper.writeValueAsString(new CreateAuditEventRequest(
                "user:99", "test.list", "thing:1", Outcome.DENIED, Map.of()));
        mvc.perform(post("/api/v1/events").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/events").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].actor").exists());
    }

    @Test
    void database_blocksUpdate() {
        jdbc.update("INSERT INTO audit_events (actor, action, resource, outcome) VALUES (?, ?, ?, ?)",
                "actor", "act", "res", "success");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE audit_events SET actor = 'hacker' WHERE id = (SELECT MAX(id) FROM audit_events)"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void database_blocksDelete() {
        jdbc.update("INSERT INTO audit_events (actor, action, resource, outcome) VALUES (?, ?, ?, ?)",
                "actor2", "act", "res", "success");

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
}
