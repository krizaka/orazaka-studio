package com.krizaka.orazaka.studioservice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.krizaka.billing.domain.model.BillableCapability;
import com.krizaka.billing.domain.model.MeteredStep;
import com.krizaka.orazaka.jobs.domain.model.CapabilityRoute;
import com.krizaka.orazaka.jobs.domain.port.CapabilityRoutingClient;
import com.krizaka.orazaka.studio.domain.model.Blueprint;
import com.krizaka.orazaka.studio.domain.model.BlueprintStatus;
import com.krizaka.orazaka.studio.domain.model.BlueprintStep;
import com.krizaka.orazaka.studio.domain.model.ErrorPolicy;
import com.krizaka.orazaka.studio.domain.model.StepKind;
import com.krizaka.orazaka.studioservice.infrastructure.support.ColumnValueResolver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

class RunSettlementServiceTest {

  private static final UUID RUN = UUID.randomUUID();
  private static final String PLAN = "orazaka.screening.plan";
  private static final String ASSESS = "orazaka.screening.documents.assess";

  private final CreditReservationService reservations = mock(CreditReservationService.class);
  private final CapabilityRoutingClient routing = mock(CapabilityRoutingClient.class);
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final RunSettlementService service =
      new RunSettlementService(
          reservations, routing, jdbc, new ColumnValueResolver(new ObjectMapper()));

  private static BlueprintStep step(String id, String featureKey) {
    return new BlueprintStep(
        id,
        StepKind.CAPABILITY,
        featureKey,
        null,
        Set.of(),
        Map.of(),
        id,
        null,
        0,
        ErrorPolicy.FAIL,
        Map.of(),
        1,
        Duration.ofMinutes(1),
        null);
  }

  private static Blueprint blueprint() {
    return new Blueprint(
        "tenant-screening-qc",
        "1.0.0",
        BlueprintStatus.PUBLISHED,
        "{}",
        List.of(step("plan", PLAN), step("assess", ASSESS)),
        List.of(),
        Map.of(),
        10L,
        "first",
        null);
  }

  private void route(String featureKey, String billable) {
    when(routing.route(featureKey))
        .thenReturn(
            Optional.of(
                new CapabilityRoute(
                    featureKey, "job.text.process", null, billable, "BATCH", true)));
  }

  @SuppressWarnings("unchecked")
  private void measuredSteps(String... stepIds) {
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            call -> {
              RowMapper<?> mapper = call.getArgument(1);
              java.util.ArrayList<Object> rows = new java.util.ArrayList<>();
              for (String id : stepIds) {
                ResultSet rs = mock(ResultSet.class);
                try {
                  when(rs.getString("step_id")).thenReturn(id);
                  when(rs.getString("model_name")).thenReturn("default");
                  when(rs.getString("consumption")).thenReturn("");
                  rows.add(mapper.mapRow(rs, rows.size()));
                } catch (SQLException e) {
                  throw new IllegalStateException(e);
                }
              }
              return rows;
            });
  }

  @Test
  @DisplayName("A step declared NONE is not metered: no charge, and it does not break the run")
  void aNotBillableStepContributesNothing() {
    route(PLAN, "NONE");
    route(ASSESS, "AGENT");
    measuredSteps("plan", "assess");

    service.settleSucceeded(RUN, "hold-1", blueprint());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<MeteredStep>> metered = ArgumentCaptor.forClass(List.class);
    verify(reservations).settle(eq("hold-1"), eq(RUN), metered.capture());
    assertThat(metered.getValue())
        .extracting(MeteredStep::capability)
        .containsExactly(BillableCapability.AGENT);
  }

  @Test
  @DisplayName("A failed run whose only measured step is NONE consumed nothing and releases")
  void aFailedRunOfNotBillableStepsReleases() {
    route(PLAN, "NONE");
    measuredSteps("plan");

    boolean consumed = service.settleFailed(RUN, "hold-2", blueprint(), null, "declined");

    assertThat(consumed).isFalse();
    verify(reservations).release("hold-2", "declined");
  }
}
