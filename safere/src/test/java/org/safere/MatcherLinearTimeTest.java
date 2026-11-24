// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Work-bound regression tests for matcher operations. */
@DisabledForCrosscheck("work accounting is a SafeRE linear-time implementation check")
@Tag("work-counter")
class MatcherLinearTimeTest {
  private static final Duration SCENARIO_TIMEOUT = Duration.ofSeconds(30);

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void captureHistoryDoesNotAddWorkBeforeDfaRejection(boolean utf8) {
    for (int repeats : new int[] {3, 12, 24}) {
      Pattern pattern = Pattern.compile("(error:\\[)[A-Z](\\] code:500)");
      String rejected = "error:[x] code:500 ".repeat(repeats) + "C";
      Utf8Input bytes = Utf8Input.validated(rejected.getBytes(UTF_8));
      Runnable reject =
          () ->
              assertThat(utf8 ? pattern.matcher(bytes).find() : pattern.matcher(rejected).find())
                  .isFalse();
      long cold = WorkCounter.countWithForwardDfaStepsForTesting(reject);
      Matcher primer = pattern.matcher("error:[C] code:500");
      assertThat(primer.find()).isTrue();
      assertThat(primer.group(1)).isEqualTo("error:[");
      long warm = WorkCounter.countWithForwardDfaStepsForTesting(reject);

      assertThat(cold).isPositive();
      assertThat(warm)
          .as("capture history must not add rejecting work, repeats=%s", repeats)
          .isLessThanOrEqualTo(cold);
    }
  }

  @Test
  @DisplayName("group access stays linear for ambiguous repeated captures")
  void groupAccessWithAmbiguousRepeatedCapturesStaysLinear() {
    Pattern pattern = Pattern.compile("((a|aa))*");

    assertNoWorkCliff(
        "matches()+group(1)",
        "a".repeat(100),
        "a".repeat(5_000),
        input -> {
          Matcher matcher = pattern.matcher(input);
          assertThat(matcher.matches()).isTrue();
          assertThat(matcher.group(1)).isEqualTo("a");
          assertThat(matcher.group(2)).isEqualTo("a");
        });
  }

  @Test
  @DisplayName("matches() stays linear for repeated dot-star with bounded captures")
  void matchesWithRepeatedDotStarAndBoundedCaptures() {
    Pattern pattern = repeatedDotStarSqlUnionPattern();

    assertNoWorkCliff(
        "matches()",
        blocks -> {
          String input = repeatedDotStarSqlUnionInput(blocks);
          Matcher matcher = pattern.matcher(input);
          assertThat(matcher.matches()).isTrue();
          assertThat(matcher.group()).isEqualTo(input);
          assertThat(matcher.start()).isEqualTo(0);
          assertThat(matcher.end()).isEqualTo(input.length());
        });
  }

  @Test
  @DisplayName("lookingAt() stays linear for repeated dot-star with bounded captures")
  void lookingAtWithRepeatedDotStarAndBoundedCaptures() {
    Pattern pattern = repeatedDotStarSqlUnionPattern();

    assertNoWorkCliff(
        "lookingAt()",
        blocks -> {
          Matcher matcher = pattern.matcher(repeatedDotStarSqlUnionInput(blocks));
          assertThat(matcher.lookingAt()).isTrue();
          assertThat(matcher.group(1)).contains("INFORMATION_SCHEMA");
        });
  }

  @Test
  @DisplayName("group access after find() stays linear for repeated dot-star captures")
  void findGroupWithRepeatedDotStarAndBoundedCaptures() {
    Pattern pattern = repeatedDotStarSqlUnionPattern();

    assertNoWorkCliff(
        "find()+group(1)",
        blocks -> {
          Matcher matcher = pattern.matcher(repeatedDotStarSqlUnionInput(blocks));
          assertThat(matcher.find()).isTrue();
          assertThat(matcher.group(1)).contains("INFORMATION_SCHEMA");
        });
  }

  @Test
  @DisplayName("region find stays linear for repeated dot-star captures")
  void regionFindWithRepeatedDotStarAndBoundedCaptures() {
    Pattern pattern = repeatedDotStarSqlUnionPattern();

    assertNoWorkCliff(
        "region().find()",
        blocks -> {
          String input = "prefix\n" + repeatedDotStarSqlUnionInput(blocks) + "suffix\n";
          Matcher matcher = pattern.matcher(input);
          matcher.region("prefix\n".length(), input.length() - "suffix\n".length());
          assertThat(matcher.find()).isTrue();
          assertThat(matcher.group(1)).contains("INFORMATION_SCHEMA");
        });
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "(a.*z)|(a)",
        "((?:aa)*z)|(a)",
        "\\G(?:(a.*z)|(a))",
        "((?:\\X)*z)|(a)",
        "((?:a?)*z)|(a)"
      })
  void stringFindSequenceStaysLinearAfterPreferredBranchFails(String regex) {
    Pattern pattern = Pattern.compile(regex);
    assertLinearSequence(
        "String find " + regex,
        size -> {
          Matcher matcher = pattern.matcher("a".repeat(size));
          for (int i = 0; i < size; i++) {
            assertThat(matcher.find()).isTrue();
            assertThat(matcher.start()).isEqualTo(i);
            assertThat(matcher.end()).isEqualTo(i + 1);
            assertThat(matcher.group(1)).isNull();
            assertThat(matcher.group(2)).isEqualTo("a");
          }
          assertThat(matcher.find()).isFalse();
        });
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "(a.*z)|(a)",
        "((?:aa)*z)|(a)",
        "\\G(?:(a.*z)|(a))",
        "((?:\\X)*z)|(a)",
        "((?:a?)*z)|(a)"
      })
  void utf8FindSequenceStaysLinearAfterPreferredBranchFails(String regex) {
    Pattern pattern = Pattern.compile(regex);
    assertLinearSequence(
        "UTF-8 find " + regex,
        size -> {
          Utf8Matcher matcher =
              pattern.matcher(Utf8Input.validated("a".repeat(size).getBytes(UTF_8)));
          for (int i = 0; i < size; i++) {
            assertThat(matcher.find()).isTrue();
            assertThat(matcher.start()).isEqualTo(i);
            assertThat(matcher.end()).isEqualTo(i + 1);
            assertThat(matcher.start(1)).isEqualTo(-1);
            assertThat(matcher.start(2)).isEqualTo(i);
          }
          assertThat(matcher.find()).isFalse();
        });
  }

  @Test
  void aLiveInstructionCanStillWinInsideAKnownFailedTail() {
    String input = "a".repeat(520) + "z";
    Matcher matcher = Pattern.compile("((?:a{17})*z)|(a.*y)|(a)").matcher(input);
    for (int i = 0; i < 10; i++) {
      assertThat(matcher.find()).isTrue();
      assertThat(matcher.start()).isEqualTo(i);
      assertThat(matcher.end()).isEqualTo(i + 1);
      assertThat(matcher.group(3)).isEqualTo("a");
    }
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(10);
    assertThat(matcher.end()).isEqualTo(input.length());
    assertThat(matcher.group(1)).isEqualTo(input.substring(10));
    assertThat(matcher.group(2)).isNull();
    assertThat(matcher.group(3)).isNull();
    assertThat(matcher.find()).isFalse();
  }

  @Test
  void repeatedSearchPreservesPriorityAndCapturesAcrossRegionsAndResets() {
    String shortMatches = "a".repeat(512);
    String input = "prefix" + shortMatches + "\naaaaz" + "suffix";
    Pattern pattern = Pattern.compile("(?:(a.*z)|(a))");
    Matcher matcher = pattern.matcher(input).region("prefix".length(), input.length() - 6);

    for (int i = 0; i < shortMatches.length(); i++) {
      assertThat(matcher.find()).isTrue();
      assertThat(matcher.start()).isEqualTo(6 + i);
      assertThat(matcher.group(1)).isNull();
      assertThat(matcher.group(2)).isEqualTo("a");
    }
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group(1)).isEqualTo("aaaaz");
    assertThat(matcher.group(2)).isNull();
    assertThat(matcher.find()).isFalse();

    matcher.reset("az");
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group(1)).isEqualTo("az");
    assertThat(matcher.group(2)).isNull();
  }

  @Test
  void lookaheadDoesNotMoveAnIndependentRegionStartAnchor() {
    String input = "p" + "a".repeat(256) + "p";
    Matcher matcher = Pattern.compile("^(a.*z|a)").matcher(input).region(1, 257);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(1);
    assertThat(matcher.end()).isEqualTo(2);
    assertThat(matcher.find()).isFalse();
  }

  @Test
  void memoizedGraphemeSearchRespectsTheOpaqueRegionClassFilter() {
    String input = "_" + "a".repeat(100) + "\uD800\uDC00".repeat(100) + "_";
    Matcher matcher =
        Pattern.compile("(?:\\X*z)?[a\\uDC00]").matcher(input).region(1, input.length() - 1);
    for (int i = 1; i <= 100; i++) {
      assertThat(matcher.find()).isTrue();
      assertThat(matcher.start()).isEqualTo(i);
    }
    assertThat(matcher.find()).isFalse();
  }

  @Test
  void replacementPreservesItsAppendPositionWhenTheSearchChangesEngines() {
    String input = "a".repeat(256) + "tail";
    Matcher matcher = Pattern.compile("a.*z|a").matcher(input);
    assertThat(matcher.replaceAll("x")).isEqualTo("x".repeat(256) + "txil");
    assertThat(matcher.appendTail(new StringBuilder()).toString()).isEmpty();
  }

  @Test
  void findResumesDfaAfterPassingThePreviouslyExploredTail() {
    int prefixLength = 256;
    String input = "a".repeat(prefixLength) + "\n" + "a\n".repeat(128);
    Matcher matcher =
        Pattern.compile("a.*z|a", 0, EnginePathOptions.builder().startRegister(false).build())
            .matcher(input);
    for (int i = 0; i < prefixLength; i++) {
      assertThat(matcher.find()).isTrue();
      assertThat(matcher.start()).isEqualTo(i);
    }
    assertThat(
            WorkCounter.countForwardDfaStepsForTesting(() -> {
              assertThat(matcher.find()).isTrue();
              assertThat(matcher.start()).isEqualTo(prefixLength + 1);
            }))
        .isPositive();
  }

  @Test
  void renderingDeferredNullableFindBoundsStillAccountsForTailWork() {
    Pattern pattern = Pattern.compile("(?:a?)*z|a");
    assertLinearSequence(
        "deferred nullable finds",
        size -> {
          Matcher matcher = pattern.matcher("a".repeat(size));
          for (int i = 0; i < size; i++) {
            assertThat(matcher.find()).isTrue();
            assertThat(matcher.toString()).contains("lastmatch=a");
          }
          assertThat(matcher.find()).isFalse();
        });
  }

  @Test
  void sparseTailCheckpointsAllowVariableWidthUtf8Decoding() {
    Pattern pattern = Pattern.compile(".*z|💡|é");
    long small = multibyteFindWork(pattern, 2_048);
    long large = multibyteFindWork(pattern, 8_192);
    assertThat(small).isPositive();
    assertThat(large).isLessThanOrEqualTo(6 * small);
  }

  @Test
  void separateLookaheadRangesDoNotRebuildAFullRegionTable() {
    Pattern pattern = Pattern.compile("a.*z|a");
    assertLinearSequence(
        "independent long branches",
        blocks -> {
          Matcher matcher = pattern.matcher(("a".repeat(256) + "\n").repeat(blocks));
          int matches = 0;
          while (matcher.find()) {
            matches++;
          }
          assertThat(matches).isEqualTo(256 * blocks);
        });
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void startVerificationDoesNotRescanAMemoizedTail(boolean utf8) {
    Pattern pattern = Pattern.compile("x*a(?:.*z)?");
    assertLinearSequence(
        "leading expansion, UTF-8=" + utf8,
        blocks -> {
          String input = ("_".repeat(64) + "a").repeat(blocks);
          int count = 0;
          if (utf8) {
            Utf8Matcher matcher = pattern.matcher(Utf8Input.validated(input.getBytes(UTF_8)));
            while (matcher.find()) {
              assertThat(matcher.start()).isEqualTo(64 + 65 * count++);
            }
          } else {
            Matcher matcher = pattern.matcher(input);
            while (matcher.find()) {
              assertThat(matcher.start()).isEqualTo(64 + 65 * count++);
            }
          }
          assertThat(count).isEqualTo(blocks);
        });
  }

  private static long multibyteFindWork(Pattern pattern, int repetitions) {
    Utf8Input input = Utf8Input.validated("💡é".repeat(repetitions).getBytes(UTF_8));
    return WorkCounter.countWithForwardDfaStepsForTesting(() -> {
      Utf8Matcher matcher = pattern.matcher(input);
      for (int i = 0; i < repetitions; i++) {
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(6 * i);
        assertThat(matcher.end()).isEqualTo(6 * i + 4);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.start()).isEqualTo(6 * i + 4);
        assertThat(matcher.end()).isEqualTo(6 * i + 6);
      }
      assertThat(matcher.find()).isFalse();
    });
  }

  private static void assertLinearSequence(String operation, IntConsumer run) {
    long small = WorkCounter.countWithForwardDfaStepsForTesting(() -> run.accept(256));
    long large = WorkCounter.countWithForwardDfaStepsForTesting(() -> run.accept(1024));
    assertThat(small).isPositive();
    assertThat(large)
        .as("%s with four times the input, small work=%d, large work=%d", operation, small, large)
        .isLessThanOrEqualTo(6 * small);
  }

  private static String repeatedDotStarSqlUnionInput(int selectCount) {
    StringBuilder input = new StringBuilder();
    for (int i = 1; i <= selectCount; i++) {
      input
          .append("(SELECT *, PARSE_DATE('%Y-%m-%d', '2025-06-25') AS snapshot_date FROM ")
          .append("`project-")
          .append("%02d".formatted(i))
          .append("`.`region2`.INFORMATION_SCHEMA.TABLE_OPTIONS)\n")
          .append("UNION ALL\n");
    }
    return input.toString();
  }

  private static Pattern repeatedDotStarSqlUnionPattern() {
    return Pattern.compile(
        ".*SELECT.*FROM.*(.*INFORMATION_SCHEMA.*){5,}.*",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  }

  private static void assertNoWorkCliff(String api, IntConsumer scenario) {
    long largerPositiveWork = countedWork(() -> scenario.accept(16));
    long nearMinimumWork = countedWork(() -> scenario.accept(5));

    assertThat(largerPositiveWork).as("%s should use matcher work", api).isPositive();
    assertThat(nearMinimumWork)
        .as(
            "%s near-minimum input should not be dramatically slower than a larger "
                + "positive input; nearWork=%d largerWork=%d",
            api, nearMinimumWork, largerPositiveWork)
        .isLessThan(largerPositiveWork * 50);
  }

  private static void assertNoWorkCliff(
      String api, String nearMinimumInput, String largerPositiveInput, Consumer<String> scenario) {
    scenario.accept(nearMinimumInput);
    scenario.accept(largerPositiveInput);
    long largerPositiveWork = countedWork(() -> scenario.accept(largerPositiveInput));
    long nearMinimumWork = countedWork(() -> scenario.accept(nearMinimumInput));

    assertThat(largerPositiveWork).as("%s should use matcher work", api).isPositive();
    assertThat(nearMinimumWork)
        .as(
            "%s near-minimum input should not be dramatically slower than a larger "
                + "positive input; nearWork=%d largerWork=%d",
            api, nearMinimumWork, largerPositiveWork)
        .isLessThan(largerPositiveWork * 50);
  }

  private static long countedWork(Runnable task) {
    return assertTimeoutPreemptively(SCENARIO_TIMEOUT, () -> WorkCounter.countForTesting(task));
  }
}
