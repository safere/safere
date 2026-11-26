// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.safere.MultiAnchorDescriptor.StartPlan;

/**
 * Plan selection for wide leading expansions whose inner plan is a literal set, and the exact
 * literal-set search they use. Behavior is covered by {@link LeadingExpansionLiteralSetsTest}.
 */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class InnerLiteralSetTest {

  @Test
  void literalSetsBehindWideClassesGetLeadingExpansion() {
    for (String regex :
        List.of(
            ".*(?:error|fatal|panic):? \\w+",
            ".*(?:apple|banana|cherry)\\b.*",
            ".*(?:apple|banana|cherry)",
            "[^\\n]*\\b(?:error|errors|panic)\\b",
            "(.*(?:error|fatal|panic)):? (\\w+)")) {
      StartPlan plan = Pattern.compile(regex).startPlan();
      assertThat(plan).as(regex).isInstanceOf(StartPlan.LeadingExpansion.class);
      assertThat(((StartPlan.LeadingExpansion) plan).innerPlan())
          .as(regex)
          .isInstanceOf(StartPlan.MultiLiteral.class);
    }
  }

  @Test
  void unselectiveLiteralSetsBehindWideClassesGetNoStartPlan() {
    for (String regex :
        List.of(
            // Literals shorter than InnerLiteralSet.MIN_LITERAL_LENGTH.
            ".*(?:ab|cd)\\b.*",
            ".*(?:abc|d)\\b.*",
            // More literals than InnerLiteralSet.MAX_LITERALS.
            ".*(?:aaa|bbb|ccc|ddd|eee|fff|ggg|hhh|iii)\\b.*",
            // A literal longer than InnerLiteralSet.MAX_LITERAL_LENGTH.
            ".*(?:abcdefghijklm|xyz)\\b.*")) {
      assertThat(Pattern.compile(regex).startPlan())
          .as(regex)
          .isNotInstanceOf(StartPlan.LeadingExpansion.class);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        ".*(?:error|fatal|panic):? \\w+",
        ".*(?:apple|banana|cherry)\\b.*",
        "(.*(?:error|fatal|panic)):? (\\w+)",
        "[^\\n]*\\b(?:error|errors|panic)\\b"
      })
  void newLeadingExpansionsKeepTheRejectPlanOfNoStartPlan(String regex) {
    // A wide expansion's inner literals stay in the reject plan, as for #1160's leading `.*`.
    int flags = Pattern.toParseFlags(0);
    Regexp ast = Parser.parse(regex, flags);
    MultiAnchorDescriptor descriptor = MultiAnchorCompiler.compile(ast, flags);
    assertThat(descriptor.startPlan()).isInstanceOf(StartPlan.LeadingExpansion.class);
    assertThat(rejectPlanString(descriptor.rejectPlan()))
        .isEqualTo(
            rejectPlanString(
                MultiAnchorCompiler.extractRejectPlan(
                    MultiAnchorCompiler.factorAlternations(ast),
                    flags,
                    StartPlan.None.INSTANCE,
                    false)));
  }

  @Test
  void literalSetFindsTheLeftmostLiteralAcrossWindows() {
    String[] literals = {"banana", "apple", "cherry"};
    InnerLiteralSet set = InnerLiteralSet.create(literals);
    for (int gap : new int[] {0, 1, 250, 254, 255, 256, 257, 700, 5000}) {
      for (String first : literals) {
        String text = "q".repeat(gap) + first + "q".repeat(gap % 13) + "apple banana";
        int expected = naiveIndexOf(text, literals, 0);
        assertThat(set.indexOf(text, 0)).as("gap %d %s", gap, first).isEqualTo(expected);
        assertThat(set.indexOf(new StringRegionInputScanner(text, 0, text.length()), 0))
            .isEqualTo(expected);
        assertThat(set.indexOf(new Utf8InputScanner(text.getBytes(UTF_8)), 0)).isEqualTo(expected);
        for (int from : new int[] {1, gap, gap + 1, text.length() - 5, text.length()}) {
          assertThat(set.indexOf(text, from))
              .as("gap %d %s from %d", gap, first, from)
              .isEqualTo(naiveIndexOf(text, literals, from));
        }
      }
    }
    // A literal crossing the end of a region does not match.
    String text = "xx apple";
    assertThat(set.indexOf(new StringRegionInputScanner(text, 0, 7), 0)).isEqualTo(-1);
    assertThat(set.indexOf(new StringRegionInputScanner(text, 3, 8), 0)).isEqualTo(0);
    assertThat(set.indexOf("", 0)).isEqualTo(-1);
  }

  @Test
  void overlappingLiteralsReportTheEarliestStart() {
    InnerLiteralSet set = InnerLiteralSet.create(new String[] {"foobar", "obar", "foo"});
    assertThat(set.indexOf("xxfoobar", 0)).isEqualTo(2);
    assertThat(set.indexOf("xxfoXobar", 0)).isEqualTo(5);
    assertThat(set.indexOf(new Utf8InputScanner("é foo".getBytes(UTF_8)), 0)).isEqualTo(3);
  }

  private static int naiveIndexOf(String text, String[] literals, int from) {
    return Arrays.stream(literals)
        .mapToInt(literal -> text.indexOf(literal, from))
        .filter(i -> i >= 0)
        .min()
        .orElse(-1);
  }

  private static String rejectPlanString(MultiAnchorDescriptor.RejectPlan plan) {
    return switch (plan) {
      case MultiAnchorDescriptor.RejectPlan.Composite composite ->
          Arrays.stream(composite.plans())
              .map(InnerLiteralSetTest::rejectPlanString)
              .toList()
              .toString();
      case MultiAnchorDescriptor.RejectPlan.DisjointLiterals disjoint ->
          "Disjoint" + Arrays.toString(disjoint.literals());
      case MultiAnchorDescriptor.RejectPlan.RequiredCharClass required ->
          "Class" + Arrays.toString(required.scanInfo().ranges());
      default -> plan.toString();
    };
  }
}
