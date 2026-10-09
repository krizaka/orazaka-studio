package com.krizaka.orazaka.studioservice.infrastructure.adapter.amqp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JobOutcomeEventTest {

  @Test
  @DisplayName("An error present is what makes an outcome a failure")
  void errorMarksFailure() {
    assertThat(new JobOutcomeEvent("job-1", null, null, null, "boom", null).failed()).isTrue();
    assertThat(new JobOutcomeEvent("job-1", Map.of(), null, null, null, null).failed()).isFalse();
    assertThat(new JobOutcomeEvent("job-1", Map.of(), null, null, "  ", null).failed()).isFalse();
  }

  @Test
  @DisplayName("A success that reported nothing is still a success")
  void emptyResultIsStillASuccess() {
    assertThat(new JobOutcomeEvent("job-1", null, null, null, null, null).outputOrEmpty())
        .isEmpty();
    assertThat(
            new JobOutcomeEvent("job-1", Map.of("assetId", "a1"), null, null, null, null)
                .outputOrEmpty())
        .containsEntry("assetId", "a1");
  }

  @Test
  @DisplayName("[ADR-041] Measurements survive the boundary, so the run can settle at their sum")
  void carriesWhatWasMeasuredAndWhatRanIt() {
    JobOutcomeEvent event =
        new JobOutcomeEvent(
            "job-1",
            Map.of("url", "/out.mp4"),
            Map.of("frames", 450, "fps", 30),
            "orazaka-compose",
            null,
            null);

    assertThat(event.consumptionOrEmpty()).containsEntry("frames", 450);
    assertThat(event.model()).isEqualTo("orazaka-compose");
  }

  @Test
  @DisplayName("A step that measured nothing yields an empty report, never a null")
  void unmeasuredStepIsEmptyNotNull() {
    assertThat(new JobOutcomeEvent("job-1", Map.of(), null, null, null, null).consumptionOrEmpty())
        .isEmpty();
  }
}
