package com.krizaka.orazaka.studio.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.krizaka.orazaka.studio.domain.port.StudioCatalogClient;
import com.krizaka.orazaka.studio.domain.port.StudioRunClient;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class StudioClientAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(StudioClientAutoConfiguration.class));

  @Test
  @DisplayName("Disabled by default: the port is satisfied by the no-op, never left unwired")
  void noOpByDefault() {
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(StudioRunClient.class);
          assertThat(context).hasSingleBean(StudioCatalogClient.class);
          assertThat(context.getBean(StudioRunClient.class))
              .isInstanceOf(NoOpStudioRunClient.class);
        });
  }

  @Test
  @DisplayName("Enabling the marketplace is a property change, never a code change")
  void httpAdaptersWhenEnabled() {
    runner
        .withPropertyValues(
            "orazaka.studio.enabled=true",
            "orazaka.studio.service-secret=orazaka-test-secret-at-least-32-characters!")
        .run(
            context -> {
              assertThat(context.getBean(StudioRunClient.class))
                  .isInstanceOf(HttpStudioRunClient.class);
              assertThat(context.getBean(StudioCatalogClient.class))
                  .isInstanceOf(HttpStudioCatalogClient.class);
            });
  }

  @Test
  @DisplayName("The no-op refuses a run rather than dropping it into silence")
  void noOpRunRefuses() {
    // A run that silently went nowhere would leave the caller waiting for an outcome that can
    // never arrive — worse than an immediate, explicit failure.
    assertThatThrownBy(() -> new NoOpStudioRunClient().start(UUID.randomUUID(), Map.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not deployed");
  }

  @Test
  @DisplayName("The no-op catalogue is empty — browsing an absent marketplace is a fair question")
  void noOpCatalogueIsEmpty() {
    assertThat(new NoOpStudioCatalogClient().browse(null)).isEmpty();
    assertThat(new NoOpStudioCatalogClient().find("trade-showcase")).isEmpty();
  }

  @Test
  @DisplayName("Defaults point at the studio service and are generous — a run is not a chat turn")
  void defaultsAreRunShaped() {
    StudioProperties properties =
        new StudioProperties(
            false, null, null, null, "orazaka-test-secret-at-least-32-characters!");

    assertThat(properties.baseUrl()).isEqualTo("http://localhost:8096");
    assertThat(properties.readTimeout().toSeconds()).isEqualTo(5);
  }
}
