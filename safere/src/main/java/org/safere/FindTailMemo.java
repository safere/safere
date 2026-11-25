// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Matcher-owned cache of NFA continuations that cannot replace a match already found.
 *
 * <p>After a candidate is accepted the NFA stops starting threads. An observed continuation past
 * the final match cannot accept. In a scalar program with no progress registers or queue-dependent
 * instructions, reachability from a queued instruction depends only on its position in the fixed
 * input and bounds. Captures affect the reported result but not reachability. Each checkpoint
 * remembers a set of failing instructions. A search can stop when that set covers all live
 * instructions at the checkpoint.
 *
 * <p>Programs that require extra state use a normalized frontier instead. Record observations past
 * the current candidate and retain those past the final result. Work before that endpoint is
 * charged to cursor advancement. Work beyond it learns a new key or reaches a known failed tail.
 * The program bounds the number of keys per position.
 */
final class FindTailMemo {
  private static final int PAGE_SHIFT = 8;
  private static final int PAGE_SIZE = 1 << PAGE_SHIFT;
  private static final int DENSE_CHECKPOINT_LIMIT = 8_192;
  private static final int SPARSE_CHECKPOINT_SHIFT = 4;

  private final Prog program;
  private final IntArrayList unproved;
  private Checkpoint[][] failed;
  private final List<Checkpoint> pending = new ArrayList<>();
  private final IntArrayList key = new IntArrayList();
  private final int bitmapWords;
  private boolean hit;
  private final int checkpointShift;
  private final int checkpointMask;
  private final int denseMask;
  private int origin;
  private int pageBase = -1;
  private int firstPage;

  FindTailMemo(Prog program, int regionLength) {
    this.program = program;
    unproved = program.supportsScalarTailMemo() ? new IntArrayList() : null;
    bitmapWords = (int) ((program.size() + 31L) / 32);
    checkpointShift = regionLength <= DENSE_CHECKPOINT_LIMIT ? 0 : SPARSE_CHECKPOINT_SHIFT;
    checkpointMask = (1 << checkpointShift) - 1;
    denseMask = unproved == null ? 0 : Math.max(0, Integer.highestOneBit(bitmapWords / 2) - 1);
  }

  Prog program() {
    return program;
  }

  boolean usesInstructionFailures() {
    return unproved != null;
  }

  void beginSearch(int origin, int start) {
    this.origin = origin;
    hit = false;
    int startPage = index(start) >>> PAGE_SHIFT;
    // Index from the activation point so independent runs do not each allocate a region-sized
    // table. An intervening anchored operation can move the cursor back before this range.
    if (pageBase < 0 || startPage < pageBase) {
      pageBase = startPage;
      failed = new Checkpoint[1][];
      firstPage = 0;
    }
    int consumedPages = Math.min(failed.length, startPage - pageBase);
    if (consumedPages > firstPage) {
      Arrays.fill(failed, firstPage, consumedPages, null);
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record(consumedPages - firstPage);
      }
    }
    firstPage = consumedPages;
    pending.clear();
  }

  boolean stoppedEarly() {
    return hit;
  }

  boolean atCheckpoint(int pos, InputScanner text, boolean visitsEveryUnit, int live) {
    int mask = checkpointMask;
    if (denseMask > mask && live >= 16) {
      // Every spacing divides the largest one. Even changing frontiers encounter a common
      // checkpoint at least once per maximum stride (allowing a code-point boundary adjustment).
      mask = Math.max(mask, Math.min(denseMask, Integer.highestOneBit(live / 8) - 1));
    }
    int offset = (pos - origin) & mask;
    // Scalar loops can step over a checkpoint inside a code point. Admit the next scalar
    // boundary in that window as well. The gap between checkpoints stays bounded.
    return offset == 0
        || (!visitsEveryUnit && offset < 4 && !text.isCodePointBoundary(pos - offset));
  }

  private int index(int pos) {
    return (pos - origin) >>> checkpointShift;
  }

  IntArrayList key() {
    key.clear();
    return key;
  }

  boolean seen(int pos) {
    int index = index(pos);
    int pageIndex = (index >>> PAGE_SHIFT) - pageBase;
    Checkpoint[] page = pageIndex < failed.length ? failed[pageIndex] : null;
    Checkpoint prior = page == null ? null : page[index & (PAGE_SIZE - 1)];
    if (usesInstructionFailures()) {
      hit = seenInstructions(pos, atPosition(prior, pos));
      return hit;
    }
    for (Checkpoint checkpoint = prior; checkpoint != null; checkpoint = checkpoint.next) {
      if (checkpoint.pos == pos && matches(checkpoint.key)) {
        hit = true;
        return true;
      }
    }
    pending.add(new Checkpoint(pos, key.toArray()));
    return false;
  }

  private static Checkpoint atPosition(Checkpoint checkpoint, int pos) {
    while (checkpoint != null && checkpoint.pos != pos) {
      checkpoint = checkpoint.next;
    }
    return checkpoint;
  }

  private boolean seenInstructions(int pos, Checkpoint prior) {
    if (WorkCounterConfig.ENABLED) {
      WorkCounter.record(key.size());
    }
    if (prior == null) {
      pending.add(instructionSet(pos, key));
      return false;
    }
    unproved.clear();
    for (int i = 0; i < key.size(); i++) {
      int instruction = key.get(i);
      if (!prior.contains(instruction)) {
        unproved.add(instruction);
      }
    }
    if (unproved.isEmpty()) {
      return true;
    }
    pending.add(instructionSet(pos, unproved));
    return false;
  }

  private Checkpoint instructionSet(int pos, IntArrayList ids) {
    if (ids.size() < bitmapWords) {
      return new Checkpoint(pos, ids.toSortedUniqueArray());
    }
    int[] words = new int[bitmapWords];
    for (int i = 0; i < ids.size(); i++) {
      int id = ids.get(i);
      words[id >>> 5] |= 1 << id;
    }
    Checkpoint checkpoint = new Checkpoint(pos, words);
    checkpoint.bitmap = true;
    return checkpoint;
  }

  private boolean matches(int[] candidate) {
    if (candidate.length != key.size()) {
      return false;
    }
    for (int i = 0; i < candidate.length; i++) {
      if (candidate[i] != key.get(i)) {
        return false;
      }
    }
    return true;
  }

  void finishSearch(int matchEnd) {
    for (Checkpoint checkpoint : pending) {
      if (checkpoint.pos > matchEnd) {
        int index = index(checkpoint.pos);
        int pageIndex = (index >>> PAGE_SHIFT) - pageBase;
        if (pageIndex >= failed.length) {
          int length = Math.max(pageIndex + 1, failed.length * 2);
          failed = Arrays.copyOf(failed, length);
          if (WorkCounterConfig.ENABLED) {
            WorkCounter.record(length);
          }
        }
        Checkpoint[] page = failed[pageIndex];
        if (page == null) {
          page = new Checkpoint[PAGE_SIZE];
          failed[pageIndex] = page;
        }
        int slot = index & (PAGE_SIZE - 1);
        if (usesInstructionFailures()) {
          Checkpoint known = atPosition(page[slot], checkpoint.pos);
          if (known != null) {
            mergeInstructions(known, checkpoint);
            continue;
          }
        }
        checkpoint.next = page[slot];
        page[slot] = checkpoint;
      }
    }
    pending.clear();
  }

  /** Merge disjoint sorted sets backwards so existing entries can stay in their array. */
  private void mergeInstructions(Checkpoint known, Checkpoint observed) {
    // Each search visits a checkpoint once and stages only instructions it has not yet proved.
    int size = known.keySize + observed.keySize;
    if (known.bitmap || observed.bitmap || size >= bitmapWords) {
      if (!known.bitmap) {
        int[] words = new int[bitmapWords];
        known.addToBitmap(words);
        known.key = words;
        known.bitmap = true;
      }
      observed.addToBitmap(known.key);
      return;
    }
    if (size > known.key.length) {
      known.key = Arrays.copyOf(known.key, Math.max(size, known.key.length * 2));
    }
    if (WorkCounterConfig.ENABLED) {
      WorkCounter.record(size);
    }
    int oldIndex = known.keySize - 1;
    int newIndex = observed.keySize - 1;
    int out = size - 1;
    while (oldIndex >= 0 && newIndex >= 0) {
      if (known.key[oldIndex] > observed.key[newIndex]) {
        known.key[out--] = known.key[oldIndex--];
      } else {
        known.key[out--] = observed.key[newIndex--];
      }
    }
    if (newIndex >= 0) {
      System.arraycopy(observed.key, 0, known.key, 0, newIndex + 1);
    }
    known.keySize = size;
  }

  private static final class Checkpoint {
    private final int pos;
    private int[] key;
    private int keySize;
    private boolean bitmap;
    private Checkpoint next;

    boolean contains(int id) {
      return bitmap
          ? (key[id >>> 5] & (1 << id)) != 0
          : Arrays.binarySearch(key, 0, keySize, id) >= 0;
    }

    void addToBitmap(int[] words) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record(bitmap ? key.length : keySize);
      }
      if (bitmap) {
        for (int i = 0; i < key.length; i++) {
          words[i] |= key[i];
        }
      } else {
        for (int i = 0; i < keySize; i++) {
          int id = key[i];
          words[id >>> 5] |= 1 << id;
        }
      }
    }

    Checkpoint(int pos, int[] key) {
      this.pos = pos;
      this.key = key;
      keySize = key.length;
    }
  }
}
