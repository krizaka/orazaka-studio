package com.krizaka.orazaka.studioservice.infrastructure.support;

import com.krizaka.orazaka.studioservice.domain.model.ContentLabel;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Test fixtures for the content policy. The committed fixtures carry neutral markers; the markers
 * are expanded here, at load time, to ordinary classifier vocabulary. The original adult prompt is
 * never committed — {@link #localAdultPrompt()} reads it from the operator's machine, when present.
 */
public final class ContentFixtures {

  public record Fixture(String id, Set<ContentLabel> expected, String prompt) {}

  private static final Map<String, String> MARKERS =
      Map.of(
          "{{EXPLICIT}}", "pornographic",
          "{{SUGGESTIVE}}", "lingerie",
          "{{GRAPHIC}}", "gory beheading",
          "{{LIKENESS}}", "deepfake",
          "{{MINOR}}", "12 year old child");

  private ContentFixtures() {}

  public static List<Fixture> all() {
    try (InputStream in =
        ContentFixtures.class.getResourceAsStream("/content-policy/fixtures.tsv")) {
      String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      return body.lines()
          .filter(line -> !line.isBlank() && !line.startsWith("#"))
          .map(
              line -> {
                String[] cells = line.split("\t", 3);
                String prompt = cells[2];
                for (var marker : MARKERS.entrySet()) {
                  prompt = prompt.replace(marker.getKey(), marker.getValue());
                }
                Set<ContentLabel> expected = EnumSet.noneOf(ContentLabel.class);
                Arrays.stream(cells[1].split(","))
                    .map(ContentLabel::valueOf)
                    .forEach(expected::add);
                return new Fixture(cells[0], expected, prompt);
              })
          .collect(Collectors.toList());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The operator's own adult prompt, if this machine has it; empty otherwise. Never printed. */
  public static java.util.Optional<String> localAdultPrompt() {
    Path path =
        Path.of(System.getProperty("user.home"), "krizaka-com", "company", "prompt-adult.md");
    try {
      return Files.isRegularFile(path)
          ? java.util.Optional.of(Files.readString(path))
          : java.util.Optional.empty();
    } catch (IOException e) {
      return java.util.Optional.empty();
    }
  }
}
