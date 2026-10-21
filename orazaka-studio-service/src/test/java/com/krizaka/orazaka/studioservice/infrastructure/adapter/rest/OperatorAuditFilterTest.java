package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * ADR-085: every operator write in the Studio service leaves one audit line — who, what, which
 * target, which request, how it ended.
 */
class OperatorAuditFilterTest {

  private final List<OperatorAuditStore.Line> lines = new ArrayList<>();
  private final OperatorAuditFilter filter = new OperatorAuditFilter(new Store(false));

  /** An in-memory store; a broken one fails on every append. */
  private final class Store implements OperatorAuditStore {
    private final boolean broken;

    Store(boolean broken) {
      this.broken = broken;
    }

    @Override
    public void append(Line line) {
      if (broken) {
        throw new IllegalStateException("db down");
      }
      lines.add(line);
    }

    @Override
    public List<Stored> recent(int limit) {
      return List.of();
    }
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
    MDC.clear();
  }

  private static void signedIn(String id, String... roles) {
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken(id, "n/a", roles));
  }

  private void call(String method, String uri, int status) throws Exception {
    var request = new MockHttpServletRequest(method, uri);
    request.setRequestURI(uri);
    var response = new MockHttpServletResponse();
    filter.doFilter(
        request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(status));
  }

  @Test
  void anAdminWriteIsAuditedWithItsTargetRequestAndOutcome() throws Exception {
    signedIn("admin-1", "ROLE_ADMIN");
    MDC.put("requestId", "req-0001-abcdef");

    call("PUT", "/api/v1/studios/video-generation/blueprints/1.12.0", 200);

    assertThat(lines).hasSize(1);
    var line = lines.getFirst();
    assertThat(line.actorId()).isEqualTo("admin-1");
    assertThat(line.action()).isEqualTo("PUT /api/v1/studios/video-generation/blueprints/{id}");
    assertThat(line.targetId()).isEqualTo("1.12.0");
    assertThat(line.requestId()).isEqualTo("req-0001-abcdef");
    assertThat(line.outcome()).isEqualTo("ok");
  }

  @Test
  void numbersAndUuidsInThePathAreTargetsNotActions() throws Exception {
    signedIn("admin-1", "ROLE_ADMIN");
    call(
        "POST",
        "/api/v1/studios/packs/bundles/550e8400-e29b-41d4-a716-446655440001/validation",
        201);
    assertThat(lines.getFirst().action())
        .isEqualTo("POST /api/v1/studios/packs/bundles/{id}/validation");
    assertThat(lines.getFirst().targetId()).isEqualTo("550e8400-e29b-41d4-a716-446655440001");
  }

  @Test
  void aRefusedOrFailedWriteIsAuditedWithItsOutcome() throws Exception {
    signedIn("admin-1", "ROLE_ADMIN");
    call("DELETE", "/api/v1/studios/packs/bundles/x", 409);
    call("PUT", "/api/v1/studios/y/blueprints/2", 500);
    call("PUT", "/api/v1/studios/z/blueprints/3", 404);
    assertThat(lines)
        .extracting(OperatorAuditStore.Line::outcome)
        .containsExactly("refused", "failed", "failed");
  }

  @Test
  void readsAndNonAdminsAreNotAudited() throws Exception {
    signedIn("admin-1", "ROLE_ADMIN");
    call("GET", "/api/v1/studios/video-generation/blueprints", 200);
    signedIn("user-1", "ROLE_USER");
    call("POST", "/api/v1/studios/video-generation/runs", 201);
    // An administrator running a Studio is not an operator action: only blueprints and bundles are
    // audited.
    signedIn("admin-1", "ROLE_ADMIN");
    call("POST", "/api/v1/studios/video-generation/runs", 201);
    SecurityContextHolder.clearContext();
    call("PUT", "/api/v1/studios/a/blueprints/1", 401);
    assertThat(lines).isEmpty();
  }

  @Test
  void aFailingStoreNeverBreaksTheRequest() throws Exception {
    signedIn("admin-1", "ROLE_ADMIN");
    var broken = new OperatorAuditFilter(new Store(true));
    var response = new MockHttpServletResponse();
    var request = new MockHttpServletRequest("PUT", "/api/v1/studios/a/blueprints/1");
    request.setRequestURI("/api/v1/studios/a/blueprints/1");

    broken.doFilter(
        request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(201));

    assertThat(response.getStatus()).isEqualTo(201);
  }
}
