// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** NFA selection must reuse prepared artifacts without adding initialization to bypass paths. */
@DisabledForCrosscheck("inspects package-private engine preparation")
class NfaProgramPreparationTest {
  @Test
  void ordinaryCapturePathsReuseTheEagerSharedProgram() throws Exception {
    for (String regex : List.of("(a|ab)+b?", "(?s).*(a.*b|c+)x?.*")) {
      Pattern pattern = Pattern.compile(regex);
      Prog prepared = preparedProgram(pattern);
      assertThat(prepared).isNotNull();
      assertThat(pattern.nfaProg(pattern.prog())).isSameAs(prepared);
      assertThat(prepared.didFlatten()).isTrue();
      String input = "aab".repeat(10_000);
      Matcher matcher = pattern.matcher(input);
      assertThat(matcher.find()).isTrue();
      assertThat(matcher.group(1)).isNotNull();
      Utf8Matcher utf8 = pattern.matcher(Utf8Input.validated(input.getBytes(UTF_8)));
      assertThat(utf8.find()).isTrue();
      assertThat(utf8.start(1)).isEqualTo(matcher.start(1));
      assertThat(utf8.end(1)).isEqualTo(matcher.end(1));
      assertThat(preparedProgram(pattern)).isSameAs(prepared);
    }
  }

  @Test
  void flatAndGraphNfaAgreeOnCapturePriorityAndBounds() {
    for (String regex :
        List.of("(a|ab)*?", "(?:(a){1,2})*?", "((a)|(ab))+?b?", "(a(b)?)+", "((é)|(éa))+?b")) {
      Pattern pattern = Pattern.compile(regex);
      Prog graph = pattern.prog();
      Prog flat = pattern.nfaProg(graph);
      assertThat(graph.didFlatten()).as(regex).isFalse();
      assertThat(flat.didFlatten()).as(regex).isTrue();
      int groups = graph.numCaptures();
      for (String input : List.of("", "a", "aa", "aab", "abab", "baabab", "éaéab", "😀aab")) {
        List<InputScanner> scanners =
            List.of(new StringInputScanner(input), new Utf8InputScanner(input.getBytes(UTF_8)));
        for (InputScanner scanner : scanners) {
          int end = scanner.length();
          for (Nfa.MatchKind kind : List.of(Nfa.MatchKind.FIRST_MATCH, Nfa.MatchKind.FULL_MATCH)) {
            int[] expected =
                Nfa.search(
                    graph, scanner, 0, end, end, 0, Nfa.Anchor.UNANCHORED, kind, groups, null);
            int[] actual =
                Nfa.search(
                    flat, scanner, 0, end, end, 0, Nfa.Anchor.UNANCHORED, kind, groups, null);
            assertThat(actual)
                .as(
                    "/%s/ on %s via %s (%s)",
                    regex, input, scanner.getClass().getSimpleName(), kind)
                .isEqualTo(expected);
          }
        }
      }
    }
  }

  @Test
  void bypassPathsDoNotPrepareAnNfaCopy() throws Exception {
    for (String regex : List.of("needle", "apple|banana|cherry")) {
      Pattern pattern = Pattern.compile(regex);
      assertThat(preparedProgram(pattern)).isNull();
      assertThat(pattern.nfaProg(pattern.prog())).isSameAs(pattern.prog());
      assertThat(pattern.matcher("xxneedlexx banana").find()).isTrue();
      assertThat(preparedProgram(pattern)).isNull();
    }
    Pattern pattern =
        Pattern.compile(
            "(a|ab)+",
            0,
            EnginePathOptions.builder().dfa(false).onePass(false).bitState(false).build());
    assertThat(pattern.nfaProg(pattern.prog())).isSameAs(pattern.prog());
    assertThat(pattern.matcher("ab".repeat(1_000)).replaceAll("<$1>"))
        .isEqualTo("<a>b".repeat(1_000));
    assertThat(preparedProgram(pattern)).isNull();
  }

  @Test
  void mixedFeaturesKeepTheGraphEvenWhenADfaProgramIsPrepared() {
    for (String regex : List.of("((a?)*)(b)", "(a|\\X)+", "\\b{g}(a)")) {
      Pattern pattern = Pattern.compile(regex);
      pattern.flatProg();
      assertThat(pattern.nfaProg(pattern.prog())).as(regex).isSameAs(pattern.prog());
    }
    Pattern pattern = Pattern.compile("(a|ab)+");
    Prog derived = Pattern.compile("(b|bc)+").prog();
    assertThat(pattern.nfaProg(derived)).isSameAs(derived);
  }

  @Test
  void concurrentFirstOptionalApiPublishesOneSharedProgram() throws Exception {
    Pattern pattern =
        Pattern.compile(
            "apple|banana|cherry",
            0,
            EnginePathOptions.builder().onePass(false).bitState(false).shiftDfa(false).build());
    assertThat(preparedProgram(pattern)).isNull();
    int threads = 16;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Prog>> results = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        results.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  assertThat(pattern.matcher("cherry").matches()).isTrue();
                  Prog selected = pattern.nfaProg(pattern.prog());
                  assertThat(selected.didFlatten()).isTrue();
                  return selected;
                }));
      }
      ready.await();
      start.countDown();
      Prog shared = results.getFirst().get();
      assertThat(shared).isSameAs(preparedProgram(pattern));
      for (Future<Prog> result : results) {
        assertThat(result.get()).isSameAs(shared);
      }
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  private static Prog preparedProgram(Pattern pattern) throws Exception {
    Field field = Pattern.class.getDeclaredField("flatProg");
    field.setAccessible(true);
    return (Prog) field.get(pattern);
  }
}
