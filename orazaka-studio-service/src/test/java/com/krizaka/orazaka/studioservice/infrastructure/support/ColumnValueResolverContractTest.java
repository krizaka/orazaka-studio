package com.krizaka.orazaka.studioservice.infrastructure.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** {@link ColumnValueResolver#conforming}: the saga's check of a declared output contract. */
class ColumnValueResolverContractTest {

  private static final String SCHEMA =
      "{\"type\":\"object\",\"required\":[\"themes\"],"
          + "\"properties\":{\"themes\":{\"type\":\"array\",\"maxItems\":3}}}";

  private final ColumnValueResolver columns = new ColumnValueResolver(new ObjectMapper());

  @Test
  @DisplayName("A chat step's text answer, fenced or not, becomes the object it holds")
  void parsesTheAnswer() {
    assertThat(columns.conforming(SCHEMA, "```json\n{\"themes\":[\"a\"]}\n```"))
        .isEqualTo("{\"themes\":[\"a\"]}");
    assertThat(columns.conforming(SCHEMA, Map.of("themes", List.of("a"))))
        .isEqualTo("{\"themes\":[\"a\"]}");
  }

  @Test
  @DisplayName("Prose where an object was asked for breaks the contract")
  void refusesProse() {
    assertThatThrownBy(() -> columns.conforming(SCHEMA, "Voici le résumé : tout va bien."))
        .isInstanceOf(OutputContractException.class)
        .hasMessageContaining("not JSON")
        .hasMessageNotContaining("tout va bien");
  }

  @Test
  @DisplayName("An object that breaks the contract is refused with its paths")
  void refusesABrokenObject() {
    assertThatThrownBy(() -> columns.conforming(SCHEMA, "{\"themes\":[1,2,3,4]}"))
        .isInstanceOfSatisfying(
            OutputContractException.class,
            broken -> assertThat(broken.violations()).containsExactly("$.themes: maxItems 3"));
  }
}
