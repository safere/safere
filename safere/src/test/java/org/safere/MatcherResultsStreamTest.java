// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for {@link Matcher#results()}. */
class MatcherResultsStreamTest {

  @Test
  @DisplayName("results() returns all matches as a stream")
  void resultsStream() {
    Pattern p = Pattern.compile("\\d+");
    Matcher m = p.matcher("abc 123 def 456 ghi");
    List<String> matches = m.results().map(MatchResult::group).collect(Collectors.toList());
    assertThat(matches).containsExactly("123", "456");
  }

  @Test
  @DisplayName("results() returns empty stream when no matches")
  void resultsNoMatch() {
    Pattern p = Pattern.compile("\\d+");
    Matcher m = p.matcher("no digits here");
    assertThat(m.results().count()).isEqualTo(0);
  }

  @Test
  @DisplayName("results() match results have correct positions")
  void resultsPositions() {
    Pattern p = Pattern.compile("[a-z]+");
    Matcher m = p.matcher("123 abc 456 def");
    List<MatchResult> results = m.results().collect(Collectors.toList());
    assertThat(results).hasSize(2);
    assertThat(results.get(0).start()).isEqualTo(4);
    assertThat(results.get(0).end()).isEqualTo(7);
    assertThat(results.get(0).hasMatch()).isTrue();
    assertThat(results.get(1).start()).isEqualTo(12);
    assertThat(results.get(1).end()).isEqualTo(15);
    assertThat(results.get(1).hasMatch()).isTrue();
  }

  @Test
  void resultsIteratorDetectsFindBetweenAdvances() {
    var matcher = Pattern.compile("a").matcher("aaaa");
    var results = matcher.results().iterator();

    assertThat(results.next().start()).isZero();
    assertThat(matcher.find()).isTrue();
    assertThatThrownBy(results::next).isInstanceOf(ConcurrentModificationException.class);
    assertThat(matcher.start()).isEqualTo(1);
  }

  @Test
  void resultsSpliteratorDetectsResetBetweenAdvances() {
    var matcher = Pattern.compile("(a)").matcher("aa");
    var results = matcher.results().spliterator();
    var starts = new ArrayList<Integer>();

    assertThat(results.tryAdvance(result -> starts.add(result.start(1)))).isTrue();
    matcher.reset("aa");
    assertThatThrownBy(() -> results.tryAdvance(result -> starts.add(result.start(1))))
        .isInstanceOf(ConcurrentModificationException.class);
    assertThat(starts).containsExactly(0);
  }

  @Test
  void resultsSpliteratorStaysExhaustedAfterMatcherChanges() {
    for (String input : List.of("a ba", "bbb")) {
      var matcher = Pattern.compile("a+").matcher(input);
      var results = matcher.results().spliterator();
      var matches = new ArrayList<String>();

      results.forEachRemaining(result -> matches.add(result.group()));
      int count = matches.size();

      matcher.reset("aaa");
      assertThat(results.tryAdvance(result -> matches.add(result.group()))).isFalse();
      matcher.region(1, 3);
      results.forEachRemaining(result -> matches.add(result.group()));

      assertThat(matches).hasSize(count);
      assertThat(matcher.find()).isTrue();
      assertThat(matcher.start()).isEqualTo(1);
    }
  }

  @Test
  void resultsIteratorStartsAfterFindBeforeFirstAdvance() {
    var matcher = Pattern.compile("a").matcher("aaa");
    var results = matcher.results().iterator();

    assertThat(matcher.find()).isTrue();
    assertThat(results.next().start()).isEqualTo(1);
    assertThat(results.next().start()).isEqualTo(2);
    assertThat(results.hasNext()).isFalse();
  }

  @Test
  void resultsSpliteratorUsesRegionSetBeforeFirstAdvance() {
    var matcher = Pattern.compile("a").matcher("aaaa");
    var results = matcher.results().spliterator();
    var starts = new ArrayList<Integer>();

    matcher.region(1, 3);
    assertThat(results.tryAdvance(result -> starts.add(result.start()))).isTrue();
    results.forEachRemaining(result -> starts.add(result.start()));
    assertThat(starts).containsExactly(1, 2);
  }

  @Test
  @DisabledForCrosscheck("generated facade advances both backing streams outside the callback")
  void resultsSpliteratorDetectsReentrantAdvanceDuringCallback() {
    var results = Pattern.compile("a").matcher("aa").results().spliterator();

    assertThatThrownBy(() ->
            results.tryAdvance(first ->
                assertThat(results.tryAdvance(second -> assertThat(second.start()).isEqualTo(1)))
                    .isTrue()))
        .isInstanceOf(ConcurrentModificationException.class);
  }

  @Test
  @DisabledForCrosscheck(
      "callback mutation is covered by MatcherStateMachineTraceTest; generated wrapper cannot "
          + "bind mutation separately to each engine")
  @DisplayName("results() detects matcher mutation during stream traversal")
  void resultsDetectsMatcherMutation() {
    Pattern p = Pattern.compile("a");
    Matcher m = p.matcher("aa");

    assertThatThrownBy(() ->
            m.results()
                .map(result -> {
                  m.find();
                  return result.group();
                })
                .collect(Collectors.toList()))
        .isInstanceOf(ConcurrentModificationException.class);
  }
}
