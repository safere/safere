// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Checks the total work of each complete operation as the input grows. */
@DisabledForCrosscheck("uses SafeRE work counters and engine diagnostics")
@Tag("work-counter")
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class CompleteSearchWorkTest {
  private static final int[] REPETITIONS = {64, 256, 1024};
  private static final String REGION_PREFIX = "éaz";
  private static final String REGION_SUFFIX = "zéa";
  private static final Utf8Input UTF8_REPLACEMENT = utf8("x");

  private enum Operation {
    STRING_FIND,
    UTF8_FIND,
    STRING_SPLIT,
    STRING_REPLACE_ALL,
    UTF8_REPLACE_ALL
  }

  private static final Set<Operation> REPEATED_SCAN_GAPS =
      Set.of(
          Operation.STRING_FIND,
          Operation.UTF8_FIND,
          Operation.STRING_SPLIT,
          Operation.STRING_REPLACE_ALL,
          Operation.UTF8_REPLACE_ALL);

  // Each repeated unit begins with one match and ends with an optional unmatched suffix.
  private record Scenario(
      String regex,
      String match,
      String unmatched,
      MatchStrategy strategy,
      boolean boundedRegion,
      Set<Operation> knownGaps) {
    @Override
    public String toString() {
      return "/" + regex + "/";
    }
  }

  private static final List<Scenario> SCENARIOS =
      List.of(
          // #1237: ruling out the preferred branch can scan the remaining input at every match.
          new Scenario("a.*z|a", "a", "", MatchStrategy.DFA, false, REPEATED_SCAN_GAPS),
          new Scenario(
              "\\G(?:a.*z|a)", "a", "", MatchStrategy.BIT_STATE, false, REPEATED_SCAN_GAPS),
          new Scenario("(?:\\X)*z|a", "a", "", MatchStrategy.NFA, false, REPEATED_SCAN_GAPS),
          // The region excludes a 'z' that could satisfy the preferred branch of the last match.
          new Scenario("a[^a]*z|a", "a", "-", MatchStrategy.DFA, true, Set.of()),
          new Scenario("\\Xz|é", "é", "-", MatchStrategy.NFA, false, Set.of()),
          new Scenario("(?m)^", "", "é\n", MatchStrategy.DFA, false, Set.of()));

  private static Stream<Arguments> operations() {
    return SCENARIOS.stream()
        .flatMap(scenario -> {
          // Split takes the whole input, and replaceAll resets the region.
          Stream<Operation> operations =
              scenario.boundedRegion()
                  ? Stream.of(Operation.STRING_FIND, Operation.UTF8_FIND)
                  : Arrays.stream(Operation.values());
          return operations.map(operation -> Arguments.of(scenario, operation));
        });
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("operations")
  void completeOperationWorkScalesWithInputLength(Scenario scenario, Operation operation) {
    Pattern pattern = Pattern.compile(scenario.regex());
    boolean knownGap = scenario.knownGaps().contains(operation);
    long[] work = new long[REPETITIONS.length];
    boolean exceededBound = false;
    for (int i = 0; i < REPETITIONS.length; i++) {
      int repetitions = REPETITIONS[i];
      Runnable task = task(pattern, scenario, operation, repetitions);
      task.run(); // Prepare lazy engines outside the measurement.
      if (i == 0) {
        long forwardSteps = WorkCounter.countForwardDfaStepsForTesting(task);
        if (scenario.strategy() == MatchStrategy.DFA) {
          assertThat(forwardSteps).as("forward DFA work").isPositive();
        } else {
          assertThat(forwardSteps).as("forward DFA work on exact fallback").isZero();
        }
      }
      work[i] = WorkCounter.countWithForwardDfaStepsForTesting(task);
      assertThat(work[i]).as("counted work at %d repetitions", repetitions).isPositive();
      if (i > 0) {
        // Each input grows by 4x. Allow 50% headroom and a small fixed cost.
        long bound = 6 * work[i - 1] + 32;
        if (knownGap) {
          exceededBound |= work[i] > bound;
        } else {
          assertThat(work[i])
              .as(
                  "work at %d repetitions after %d units at the previous size",
                  repetitions, work[i - 1])
              .isLessThanOrEqualTo(bound);
        }
      }
    }
    if (knownGap) {
      assertThat(exceededBound)
          .as("work %s now meets the bound; remove its known gap for #1237", Arrays.toString(work))
          .isTrue();
    }
  }

  private static Stream<Scenario> exactScenarios() {
    return SCENARIOS.stream().filter(scenario -> scenario.strategy() != MatchStrategy.DFA);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("exactScenarios")
  void findSequencesUseTheExactFallback(Scenario scenario) {
    Pattern pattern = Pattern.compile(scenario.regex());
    EnumSet<MatchStrategy> strategies = EnumSet.noneOf(MatchStrategy.class);
    SafeReMatchDiagnostics previous = Pattern.diagnostics();
    try {
      Pattern.setDiagnostics(
          new SafeReMatchDiagnostics() {
            @Override
            public void onOperationCompleted(OperationDiagnostics event) {
              if (event.outcome() == MatchOutcome.MATCH) {
                strategies.add(event.boundaryStrategy());
              }
            }
          });
      for (Operation operation : List.of(Operation.STRING_FIND, Operation.UTF8_FIND)) {
        strategies.clear();
        task(pattern, scenario, operation, REPETITIONS[0]).run();
        assertThat(strategies)
            .as("successful matches for %s", operation)
            .containsExactly(scenario.strategy());
      }
    } finally {
      Pattern.setDiagnostics(previous);
    }
  }

  private static Runnable task(
      Pattern pattern, Scenario scenario, Operation operation, int repetitions) {
    String unit = scenario.match() + scenario.unmatched();
    String body = unit.repeat(repetitions);
    String prefix = scenario.boundedRegion() ? REGION_PREFIX : "";
    String input = scenario.boundedRegion() ? prefix + body + REGION_SUFFIX : body;
    return switch (operation) {
      case STRING_FIND ->
          () -> {
            Matcher matcher = pattern.matcher(input);
            if (scenario.boundedRegion()) {
              matcher.region(prefix.length(), prefix.length() + body.length());
            }
            checkFinds(
                matcher::find,
                matcher::start,
                matcher::end,
                repetitions,
                prefix.length(),
                unit.length(),
                scenario.match().length());
          };
      case UTF8_FIND -> {
        Utf8Input bytes = utf8(input);
        int start = utf8(prefix).length();
        int stride = utf8(unit).length();
        int width = utf8(scenario.match()).length();
        yield () -> {
          Utf8Matcher matcher = pattern.matcher(bytes);
          if (scenario.boundedRegion()) {
            matcher.region(start, start + stride * repetitions);
          }
          checkFinds(
              matcher::find, matcher::start, matcher::end, repetitions, start, stride, width);
        };
      }
      case STRING_SPLIT -> {
        // An empty match at the start does not produce a leading empty part.
        boolean consumes = !scenario.match().isEmpty();
        String[] expected = new String[repetitions + (consumes ? 1 : 0)];
        Arrays.fill(expected, scenario.unmatched());
        if (consumes) {
          expected[0] = "";
        }
        yield () -> assertThat(pattern.split(input, -1)).containsExactly(expected);
      }
      case STRING_REPLACE_ALL -> {
        String expected = ("x" + scenario.unmatched()).repeat(repetitions);
        yield () -> assertThat(pattern.matcher(input).replaceAll("x")).isEqualTo(expected);
      }
      case UTF8_REPLACE_ALL -> {
        Utf8Input bytes = utf8(input);
        String expected = ("x" + scenario.unmatched()).repeat(repetitions);
        yield () -> {
          ByteArrayOutputStream output = new ByteArrayOutputStream();
          pattern.matcher(bytes).replaceAll(output::write, UTF8_REPLACEMENT);
          assertThat(output.toString(UTF_8)).isEqualTo(expected);
        };
      }
    };
  }

  private static void checkFinds(
      BooleanSupplier find,
      IntSupplier start,
      IntSupplier end,
      int repetitions,
      int first,
      int stride,
      int width) {
    for (int i = 0; i < repetitions; i++) {
      int expectedStart = first + i * stride;
      assertThat(find.getAsBoolean()).as("find at %d", expectedStart).isTrue();
      assertThat(start.getAsInt()).isEqualTo(expectedStart);
      assertThat(end.getAsInt()).isEqualTo(expectedStart + width);
    }
    assertThat(find.getAsBoolean()).as("find after the last expected match").isFalse();
  }

  private static Utf8Input utf8(String text) {
    return Utf8Input.trusted(text.getBytes(UTF_8));
  }
}
