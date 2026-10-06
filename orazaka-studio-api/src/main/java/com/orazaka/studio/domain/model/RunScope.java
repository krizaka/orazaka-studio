package com.orazaka.studio.domain.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything a step's templates may see, and the <b>sole owner</b> of the Studio templating grammar
 * (ADR-034 §1, design §6.2).
 *
 * <p>The scope resolves its own placeholders — a smart payload, not a {@code TemplateUtil}
 * (ERR-127). Concentrating the grammar in one type is also what makes the security argument
 * checkable: a blueprint is untrusted input even from an admin, so the grammar is deliberately
 * <b>not Turing-complete</b> and there is exactly one place to audit.
 *
 * <p>Supported placeholder forms, and <b>nothing else</b>:
 *
 * <ul>
 *   <li>{@code {{item}}} — the current element inside a {@code forEach}
 *   <li>{@code {{inputs.x}}} — a run input
 *   <li>{@code {{config.x}}} and bare {@code {{config}}} — the installation's configuration
 *   <li>{@code {{secrets.x}}} — a connector credential; readable here, never rendered into a log,
 *       an event payload or a run-detail response
 *   <li>{@code {{steps.out}}} and {@code {{steps.out.field}}} — an upstream step's output
 *   <li>a {@code {{ref:default}}} fallback on any of the above — the same {@code ${key:default}}
 *       grammar {@code orazaka_capabilities.payload_template} already defines, so the platform has
 *       one templating grammar rather than two
 * </ul>
 *
 * <p>And four condition forms: {@code <ref> == <literal>}, {@code <ref> != <literal>}, {@code <ref>
 * is null}, {@code <ref> is not null}. Anything richer is a new {@link StepKind}, not a new
 * expression: a sandbox escape in a user-authored template is unrecoverable, whereas a new step
 * kind is cheap and reviewable.
 *
 * <p>An unrecognised <i>form</i> always throws. A recognised form whose <i>value</i> is absent
 * renders its default, or the empty string when it declares none — an optional input that was not
 * supplied is not a malformed template.
 *
 * @param inputs the run's inputs, validated against the blueprint's JSON Schema before the scope
 *     exists; defensively copied
 * @param config the installation's configuration — brand kit, tone, signature; defensively copied
 * @param steps upstream outputs keyed by each step's {@code out} name; a value that is itself a map
 *     is what makes {@code {{steps.out.field}}} resolvable; defensively copied
 * @param item the current element inside a {@code forEach}, {@code null} outside one
 * @param secrets connector credentials scoped to the actor; defensively copied and excluded from
 *     {@link #toString()}
 */
public record RunScope(
    Map<String, Object> inputs,
    Map<String, String> config,
    Map<String, Object> steps,
    Object item,
    Map<String, String> secrets) {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}]*)}}");

  /** A step input that is nothing but one placeholder — the only shape that can carry structure. */
  private static final Pattern LONE_PLACEHOLDER = Pattern.compile("\\s*\\{\\{([^{}]*)}}\\s*");

  private static final String NAME = "[A-Za-z][A-Za-z0-9_]*";

  /**
   * The closed set of references. Everything outside it is a template error, never an empty render.
   */
  private static final Pattern REFERENCE =
      Pattern.compile(
          "item"
              + "|inputs\\."
              + NAME
              + "|config(?:\\."
              + NAME
              + ")?"
              + "|secrets\\."
              + NAME
              + "|steps\\."
              + NAME
              + "(?:\\."
              + NAME
              + ")?");

  private static final String LITERAL = "null|true|false|-?\\d+|'[^']*'|\"[^\"]*\"";

  private static final Pattern CONDITION =
      Pattern.compile(
          "\\s*(\\{\\{[^{}]*}})\\s*(?:(==|!=)\\s*(" + LITERAL + ")|is\\s+(not\\s+)?null)\\s*");

  private static final String NULL_LITERAL = "null";

  /**
   * Compact canonical constructor: defensive copies, so a scope cannot mutate under a running DAG.
   */
  public RunScope {
    inputs = copy(inputs);
    steps = copy(steps);
    config = copy(config);
    secrets = copy(secrets);
  }

  /**
   * Renders a template by substituting every placeholder it contains.
   *
   * @param template the raw template, e.g. {@code "Ton: {{config.tone}}"}
   * @return the rendered string
   * @throws IllegalArgumentException if any placeholder is not one of the supported forms
   * @throws NullPointerException if {@code template} is null
   */
  public String resolve(String template) {
    Objects.requireNonNull(template, "template must not be null");
    Matcher matcher = PLACEHOLDER.matcher(template);
    StringBuilder rendered = new StringBuilder();
    while (matcher.find()) {
      String value = resolveToken(matcher.group(1));
      matcher.appendReplacement(rendered, Matcher.quoteReplacement(value == null ? "" : value));
    }
    matcher.appendTail(rendered);
    return rendered.toString();
  }

  /**
   * Resolves a step input to its <b>value</b>, keeping a list a list.
   *
   * <p>{@link #resolve} renders for prose and is right for a prompt: a fan-out's results become one
   * line each. It was also every step input's only option, so a composition step declaring {@code
   * photos: "{{inputs.photos}}"} handed its executor those ids joined by newlines — one unusable
   * string where a list of assets was meant. {@code realestate-reels} failed on it, and was
   * diagnosed three times against layers that were each wrong before this one (ADR-046).
   *
   * <p>The rule is narrow on purpose: <b>only</b> a template that is exactly one placeholder can
   * carry structure, because only then is there no surrounding text to put it in. Anything else — a
   * placeholder inside a sentence, a literal, several placeholders — renders exactly as {@link
   * #resolve} always did, so no prompt in any shipped blueprint changes.
   *
   * @param template the raw step input, e.g. {@code "{{inputs.photos}}"} or {@code "9x16"}
   * @return the referenced value when the template is a lone placeholder over a list, otherwise the
   *     rendered string
   * @throws NullPointerException if {@code template} is null
   */
  public Object resolveValue(String template) {
    Objects.requireNonNull(template, "template must not be null");
    Matcher lone = LONE_PLACEHOLDER.matcher(template);
    if (lone.matches()) {
      Object value = lookup(reference(lone.group(1)));
      if (value instanceof List<?>) {
        return value;
      }
    }
    return resolve(template);
  }

  /**
   * Evaluates one of the four supported conditions against this scope.
   *
   * @param condition e.g. {@code "{{inputs.clip}} is null"} or {@code "{{config.tone}} ==
   *     'premium'"}
   * @return whether the condition holds — a step whose condition is false is skipped, not failed
   * @throws IllegalArgumentException if the condition is not one of the four supported forms
   * @throws NullPointerException if {@code condition} is null
   */
  public boolean matches(String condition) {
    Objects.requireNonNull(condition, "condition must not be null");
    Matcher matcher = CONDITION.matcher(condition);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Unsupported condition: " + condition);
    }
    String resolved = resolveToken(stripBraces(matcher.group(1)));
    String operator = matcher.group(2);
    if (operator == null) {
      boolean negated = matcher.group(4) != null;
      return negated == (resolved != null);
    }
    boolean equal = Objects.equals(resolved, unquote(matcher.group(3)));
    return "==".equals(operator) == equal;
  }

  /**
   * The same scope with a different {@code forEach} item.
   *
   * <p>A wither rather than a mutable field: every fan-out instance resolves its own templates
   * concurrently, and a shared scope whose {@code item} moved under them would silently render the
   * wrong photo into the wrong caption.
   *
   * @param element the current element, {@code null} outside a fan-out
   * @return a copy bound to that element
   */
  public RunScope withItem(Object element) {
    return new RunScope(inputs, config, steps, element, secrets);
  }

  /**
   * Resolves a fan-out source into the items to iterate.
   *
   * <p>Returns the <b>raw</b> value rather than its rendering, because a fan-out iterates a list of
   * asset ids, not a string that happens to look like one. A reference to a single value yields one
   * item, so {@code forEach} over a non-list is a fan-out of one rather than an error — the
   * blueprint author's intent is still expressible.
   *
   * @param template a single placeholder, e.g. {@code {{inputs.photos}}}
   * @return the items to fan out over; empty when the reference resolves to nothing
   * @throws IllegalArgumentException if the placeholder is not a supported form
   */
  public List<Object> expand(String template) {
    Objects.requireNonNull(template, "template must not be null");
    Matcher matcher = PLACEHOLDER.matcher(template.trim());
    if (!matcher.matches()) {
      throw new IllegalArgumentException(
          "a forEach source must be a single placeholder: " + template);
    }
    Object value = lookup(reference(matcher.group(1)));
    if (value == null) {
      return List.of();
    }
    return value instanceof List<?> list ? List.copyOf(list) : List.of(value);
  }

  /**
   * Asserts that every placeholder in a template is a supported form, without needing a scope.
   *
   * <p>The grammar check a blueprint runs at authoring time. Whether a reference <i>resolves</i>
   * against the input schema and the upstream outputs is a richer question that needs a JSON Schema
   * reader, and therefore belongs to the service rather than to this dependency-free contract.
   *
   * @param template the template to check; {@code null} is accepted as "no template"
   * @throws IllegalArgumentException if any placeholder is not one of the supported forms
   */
  public static void assertValidGrammar(String template) {
    if (template == null) {
      return;
    }
    Matcher matcher = PLACEHOLDER.matcher(template);
    while (matcher.find()) {
      reference(matcher.group(1));
    }
  }

  /**
   * Redacts the credentials. Secrets are readable by {@link #resolve} and by nothing else — a scope
   * that printed them would leak them into every log line and error report that carries it.
   *
   * @return a debug rendering with the secret values replaced by their count
   */
  @Override
  public String toString() {
    return "RunScope[inputs="
        + inputs
        + ", config="
        + config
        + ", steps="
        + steps
        + ", item="
        + item
        + ", secrets=<redacted:"
        + secrets.size()
        + ">]";
  }

  /** Resolves one token, applying its {@code :default} fallback; null when neither is available. */
  private String resolveToken(String token) {
    String reference = reference(token);
    int separator = token.indexOf(':');
    String fallback = separator < 0 ? null : token.substring(separator + 1).trim();
    Object value = lookup(reference);
    return value == null ? fallback : render(value);
  }

  /**
   * Renders a resolved value as the text a prompt actually wants.
   *
   * <p>{@code String.valueOf} is wrong for the two shapes this scope routinely holds. A fan-out
   * output is a <b>list</b> of results, and a step output is a <b>map</b> of the executor's return
   * fields — rendering either through {@code toString} puts {@code [{content=…}, {content=…}]} into
   * a prompt, which is Java syntax leaking into a model's input.
   *
   * <p>Two rules, both mechanical rather than field-name magic: a list renders as its elements one
   * per line, and a single-entry map renders as that entry's value. A map with several entries has
   * no obvious reading, so it renders its pairs and the blueprint author is expected to say which
   * one they meant with {@code {{steps.out.field}}}.
   */
  private static String render(Object value) {
    if (value instanceof List<?> elements) {
      StringBuilder joined = new StringBuilder();
      for (Object element : elements) {
        if (!joined.isEmpty()) {
          joined.append('\n');
        }
        joined.append(render(element));
      }
      return joined.toString();
    }
    if (value instanceof Map<?, ?> fields) {
      if (fields.size() == 1) {
        return render(fields.values().iterator().next());
      }
      StringBuilder joined = new StringBuilder();
      fields.forEach(
          (key, entry) -> {
            if (!joined.isEmpty()) {
              joined.append(", ");
            }
            joined.append(key).append(": ").append(render(entry));
          });
      return joined.toString();
    }
    return String.valueOf(value);
  }

  private Object lookup(String reference) {
    int firstDot = reference.indexOf('.');
    String root = firstDot < 0 ? reference : reference.substring(0, firstDot);
    String path = firstDot < 0 ? "" : reference.substring(firstDot + 1);
    return switch (root) {
      case "item" -> item;
      case "inputs" -> inputs.get(path);
      case "secrets" -> secrets.get(path);
      case "config" -> path.isEmpty() ? config : config.get(path);
      case "steps" -> step(path);
      default -> null;
    };
  }

  /** {@code out} yields the whole output; {@code out.field} reaches inside a structured one. */
  private Object step(String path) {
    int dot = path.indexOf('.');
    Object output = steps.get(dot < 0 ? path : path.substring(0, dot));
    if (dot < 0 || !(output instanceof Map<?, ?> fields)) {
      return dot < 0 ? output : null;
    }
    return fields.get(path.substring(dot + 1));
  }

  /**
   * Splits a token into its reference and validates it, which is the whole anti-corruption check.
   */
  private static String reference(String token) {
    int separator = token.indexOf(':');
    String reference = (separator < 0 ? token : token.substring(0, separator)).trim();
    if (!REFERENCE.matcher(reference).matches()) {
      throw new IllegalArgumentException("Unsupported template reference: {{" + token + "}}");
    }
    return reference;
  }

  private static String stripBraces(String placeholder) {
    return placeholder.substring(2, placeholder.length() - 2);
  }

  /** {@code null} is the absent value, not the four-letter string; quotes are delimiters. */
  private static String unquote(String literal) {
    if (NULL_LITERAL.equals(literal)) {
      return null;
    }
    boolean quoted =
        literal.length() >= 2
            && (literal.startsWith("'") && literal.endsWith("'")
                || literal.startsWith("\"") && literal.endsWith("\""));
    return quoted ? literal.substring(1, literal.length() - 1) : literal;
  }

  private static <V> Map<String, V> copy(Map<String, V> source) {
    return source == null ? Map.of() : Map.copyOf(source);
  }
}
