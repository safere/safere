// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.MatchResult;
import org.junit.jupiter.api.Test;

/**
 * Tests for leading expansions over a wide class (such as non-DOTALL {@code .*}) whose inner plan
 * is a set of literals, as in {@code .*(?:error|fatal|panic):? \w+}. Behavior is compared with
 * {@code java.util.regex} across entry points, regions and UTF-8 input.
 */
class LeadingExpansionLiteralSetsTest {

  // Start candidates are only chosen by walking back for skips of at least
  // MultiAnchorCompiler.MIN_WIDE_LEADING_SKIP units, so inputs are also run behind padding.
  private static final String PAD = "p".repeat(300);

  private static final List<String> REGEXES =
      List.of(
          ".*(?:error|fatal|panic):? \\w+",
          ".*(error|fatal|panic)\\b.*",
          "(.*(?:error|fatal|panic)):? (\\w+)",
          ".+?(?:fatal|error)(\\d)?",
          "[^\\n]*\\b(?:error|errors|panic)\\b",
          "[^;]*(?:error|panic): (\\w+)",
          ".*(?:foo|foobar)(bar)?",
          ".*(?:apple|banana|cherry).*",
          "(?i).*(?:error|fatal|panic):? \\w+");

  private static final List<String> INPUTS =
      List.of(
          "",
          "error: x",
          "fatal: ",
          "no match here\nnor here\n",
          "first line\nsecond panic: one\nthird",
          "error: at start\nmiddle fatal two\n\nerror: three",
          "\n\n\nfatal: after empty lines\n\n",
          "a error: \nb panic: c",
          "crlf\r\nline error: one\r\nnext fatal: two\r\n",
          "lone cr\rthen panic: x\rerror: y",
          "nel\u0085x error: a\u2028y fatal: b\u2029z panic: c",
          "semi;colon error: x;y error: z",
          "😀😀 error: smile\n😀fatal: 😀\né panic: é",
          "error: fatal: panic: x",
          "errorerror: x error1 panicfatal7",
          "FATAL: upper\nError: mixed",
          "foobarbar foo foobar\nfoob",
          """
          2026-11-07 INFO starting
          2026-11-07 WARN error: -
          2026-11-07 INFO panic \t
          2026-11-07 ERROR fatal: disk
          """);

  @Test
  void findAgreesWithJdk() {
    for (int flags : new int[] {0, Pattern.UNIX_LINES, Pattern.MULTILINE}) {
      for (String regex : REGEXES) {
        Pattern pattern = Pattern.compile(regex, flags);
        java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(regex, flags);
        for (String input : paddedInputs()) {
          String what = "%s (flags %d) on %s".formatted(regex, flags, input);
          assertThat(findAll(pattern.matcher(input)))
              .as(what)
              .isEqualTo(findAll(jdk.matcher(input)));
          assertThat(findAllUtf8(pattern, input))
              .as("UTF-8 " + what)
              .isEqualTo(jdkUtf8(jdk, input));
          boolean found = jdk.matcher(input).find();
          assertThat(pattern.asPredicate().test(input)).as("predicate " + what).isEqualTo(found);
          assertThat(pattern.find(Utf8Input.trusted(input.getBytes(UTF_8))))
              .as("UTF-8 find " + what)
              .isEqualTo(found);
          assertThat(pattern.matcher(input).matches())
              .as("matches " + what)
              .isEqualTo(jdk.matcher(input).matches());
          assertThat(pattern.matcher(input).replaceAll("<$0>"))
              .as("replaceAll " + what)
              .isEqualTo(jdk.matcher(input).replaceAll("<$0>"));
          assertThat(pattern.split(input, -1)).as("split " + what).isEqualTo(jdk.split(input, -1));
          // Within the padding, every start behaves alike, so sample it sparsely.
          int padded = input.startsWith(PAD) ? PAD.length() : 0;
          for (int from = 0; from <= input.length(); from += from < padded - 8 ? 31 : 1) {
            java.util.regex.Matcher expected = jdk.matcher(input);
            Matcher actual = pattern.matcher(input);
            boolean foundFrom = expected.find(from);
            assertThat(actual.find(from)).as("%s from %d", what, from).isEqualTo(foundFrom);
            if (foundFrom) {
              assertThat(groups(actual)).as("%s from %d", what, from).isEqualTo(groups(expected));
            }
          }
        }
      }
    }
  }

  @Test
  void regionsAgreeWithJdk() {
    String lines = "aa error: one\nbb fatal: two\r\ncc panic three";
    regionsAgreeWithJdk(lines, 3, 4);
    regionsAgreeWithJdk(PAD + "\n" + lines, 13, 17);
    regionsAgreeWithJdk(PAD + lines, 13, 17);
  }

  private static void regionsAgreeWithJdk(String input, int startStep, int endStep) {
    for (String regex : REGEXES) {
      Pattern pattern = Pattern.compile(regex);
      java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(regex);
      for (int start = 0; start <= input.length(); start += startStep) {
        for (int end = start; end <= input.length(); end += endStep) {
          for (boolean transparent : new boolean[] {false, true}) {
            java.util.regex.Matcher expected = jdk.matcher(input);
            expected.region(start, end).useTransparentBounds(transparent);
            Matcher actual = pattern.matcher(input);
            actual.region(start, end).useTransparentBounds(transparent);
            assertThat(findAll(actual))
                .as("%s region [%d, %d) transparent=%s", regex, start, end, transparent)
                .isEqualTo(findAll(expected));
          }
        }
      }
    }
  }

  @Test
  void denseLiteralHitsWithFailingTailsAgreeWithJdk() {
    // Almost every line holds an inner literal, but only some tails match.
    StringBuilder sb = new StringBuilder(PAD).append('\n');
    String[] words = {"error", "fatal", "panic"};
    for (int line = 0; line < 400; line++) {
      sb.append("line ").append(line).append(' ').append(words[line % 3]);
      sb.append(line % 7 == 0 ? ": ok" : line % 5 == 0 ? " -" : ":");
      sb.append(line % 11 == 0 ? "\r\n" : "\n");
    }
    String input = sb.toString();
    for (String regex : REGEXES) {
      Pattern pattern = Pattern.compile(regex);
      java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(regex);
      assertThat(findAll(pattern.matcher(input))).as(regex).isEqualTo(findAll(jdk.matcher(input)));
      assertThat(findAllUtf8(pattern, input)).as("UTF-8 " + regex).isEqualTo(jdkUtf8(jdk, input));
      assertThat(pattern.matcher(input).replaceAll("[$0]"))
          .as("replaceAll " + regex)
          .isEqualTo(jdk.matcher(input).replaceAll("[$0]"));
    }
  }

  private static List<String> paddedInputs() {
    List<String> inputs = new ArrayList<>();
    for (String input : INPUTS) {
      inputs.add(input);
      inputs.add(PAD + "\n" + input);
      inputs.add(PAD + input);
    }
    return inputs;
  }

  private static List<String> findAll(Matcher matcher) {
    List<String> out = new ArrayList<>();
    while (matcher.find()) {
      out.add(groups(matcher));
    }
    return out;
  }

  private static List<String> findAll(java.util.regex.Matcher matcher) {
    List<String> out = new ArrayList<>();
    while (matcher.find()) {
      out.add(groups(matcher));
    }
    return out;
  }

  private static String groups(MatchResult result) {
    StringBuilder sb = new StringBuilder();
    for (int group = 0; group <= result.groupCount(); group++) {
      sb.append(result.start(group)).append(':').append(result.end(group)).append(' ');
    }
    return sb.toString();
  }

  private static List<String> findAllUtf8(Pattern pattern, String input) {
    Utf8Matcher matcher = pattern.matcher(Utf8Input.trusted(input.getBytes(UTF_8)));
    List<String> out = new ArrayList<>();
    while (matcher.find()) {
      StringBuilder sb = new StringBuilder();
      for (int group = 0; group <= matcher.groupCount(); group++) {
        sb.append(matcher.start(group)).append(':').append(matcher.end(group)).append(' ');
      }
      out.add(sb.toString());
    }
    return out;
  }

  /** Returns the JDK's matches with bounds converted to UTF-8 byte offsets. */
  private static List<String> jdkUtf8(java.util.regex.Pattern jdk, String input) {
    java.util.regex.Matcher matcher = jdk.matcher(input);
    List<String> out = new ArrayList<>();
    while (matcher.find()) {
      StringBuilder sb = new StringBuilder();
      for (int group = 0; group <= matcher.groupCount(); group++) {
        int start = matcher.start(group);
        sb.append(start < 0 ? -1 : byteOffset(input, start))
            .append(':')
            .append(start < 0 ? -1 : byteOffset(input, matcher.end(group)))
            .append(' ');
      }
      out.add(sb.toString());
    }
    return out;
  }

  private static int byteOffset(String input, int charIndex) {
    return input.substring(0, charIndex).getBytes(UTF_8).length;
  }
}
