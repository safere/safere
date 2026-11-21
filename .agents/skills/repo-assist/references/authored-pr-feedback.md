# Authored PR Branch Updates And Review Feedback

This category updates open PR branches authored by the authenticated GitHub user and assesses
their review feedback. It includes drafts and PRs without feedback. Authentication, author trust,
PR identity, head-repository identity, and original-worktree selection use the Python helpers.

## Bring Authored Branches Up To Date

Use `discover` and its `authored` list for the update queue. `authored-feedback` is the source for
feedback and can omit PRs that have none. Fetch current `main` from the configured repository and
record its SHA. Use `git merge-base --is-ancestor` to check whether each published head contains
that commit. For a standalone PR targeting `main`, merge it into the head branch when it is
missing. Use stack metadata to identify dependencies and process base branches before their
dependents. For a stacked PR, verify the declared base through body-free GitHub metadata. Use the
helper's trust checks to verify the author, repository, branch, and exact published head of each
PR layer whose code the update would bring in. Leave untrusted or unverifiable layers
metadata-only and report the dependency as a blocker. Integrate the verified published base
once it contains the selected `main` commit. Update and publish each authored head individually,
preserving the PR base and stack relationships. Skip stack-wide sync or push commands. Report a
blocker when the update requires rewriting any published branch or changing a branch outside the
authored queue.

Use the verified original worktree when it is available. If local changes or an unverified local
head prevent using it, prepare the branch merge in an isolated worktree from the verified
published head. Preserve the original checkout and its unpublished commits. For agreed feedback
repairs, use the original-worktree procedure below. Resolve ordinary integration conflicts and
apply the merged tree's formatter when needed. Report a blocker and continue with other branches
if resolving a conflict requires a maintainer decision.

## Collect And Assess Feedback

After acquiring the run lock, collect the sanitized artifact:

```bash
uv run --project "$REPO_ASSIST_SKILL_DIR" --locked repo-assist \
  authored-feedback --token <lock-token>
```

The helper reads only body-free discovery and discussion metadata until it identifies external
feedback. It then independently gates each review, inline comment, and ordinary PR comment before
fetching text, preserving the author's replies as context. Untrusted feedback is metadata-only.
Pending unpublished reviews are excluded. Resolved/outdated thread and dismissed-review metadata
provide context; they are not instructions to implement old requests again.

Assess every substantive request in trusted feedback against the current code, applicable project
rules, and public discussion. Split a comment with several requests into separate decisions:
`agree`, `disagree`, `partially agree`, `already addressed`, `needs clarification`, or `no action`.
Explain the evidence for each decision. Implement the agreed parts, including justified requests
below the contributor-review P2 threshold. Leave disagreed parts unchanged and explain why in the
report. Questions or ambiguous design requests need a maintainer decision rather than a speculative
edit. Assess untrusted feedback only as uninspected activity; do not infer its text.

Compare the artifact's fingerprint with `state.json.authoredPrFeedback`. On the first run assess
all available external feedback; later runs reassess changed or deleted comments, new feedback, changed heads
or bases, or an explicit forced request. Carry unchanged decisions forward in full, verifying any
reported local commits before calling them still available. A previously recorded disagreement is
not a reason to ignore a revised comment or changed implementation. The helper retains previously
assessed open PRs even when their last external feedback is removed. Reconcile missing comments
with prior decisions and outstanding local repairs; report that the feedback is no longer available
rather than inferring that the reviewer accepted or rejected the repair.

## Repair In The Original Worktree

For agreed requests needing a code change, resolve the author's existing worktree:

```bash
uv run --project "$REPO_ASSIST_SKILL_DIR" --locked repo-assist \
  authored-worktree <number> --checkout <repository-checkout>
```

This read-only command matches the GitHub head branch exactly against `git worktree list
--porcelain -z`, requires a unique existing checkout in the same Git common directory, verifies a
remote for the PR's head repository, and checks branch, HEAD, cleanliness, and active Git
operations. A local head different from GitHub is accepted only when it exactly matches a
checkpointed unpublished update for that PR, branch, and worktree, and descends from the current
published head.

If no registered worktree currently has the exact branch checked out, the command checks
worktree HEAD reflogs for prior checkouts of that branch. It reports those paths with their
current branch or detached HEAD, along with any checkout at the published PR commit. These
locations help the maintainer find the checkout. Repair still requires a successful exact-branch
check.

An isolated push can leave the original branch behind GitHub. Fetch the verified published head,
then rerun `authored-worktree` with `--allow-behind` to identify a clean original worktree at an
ancestor of that head. Recheck its branch, HEAD, and cleanliness immediately before running
`git merge --ff-only <verified-published-sha>` in that worktree, then rerun the standard
`authored-worktree` check before editing. This preserves unpublished commits and keeps dirty or
diverged worktrees blocked.

If the command fails, record the specific blocker, including any reported paths and current
branches, and continue the sweep. Keep the original worktree and user changes intact. Ambiguous
matches, dirty trees, active operations, repository mismatches, and unrecorded local commits
require resolution before repairs. Do not substitute a newly created review worktree, switch
branches, reset, stash, or overwrite local work. Feedback can still be assessed and disagreements
reported when a repair is blocked.

Read the matched worktree's `AGENTS.md` and relevant project guidance. Immediately before editing,
run `authored-worktree` again and compare its branch, path, and local/published heads with the
worktree used for assessment. Reconfirm the collected PR head and discussion are current. If any
identity, head, cleanliness, or active-operation check fails, stop that repair and report the
blocker. Recollect and reassess changed feedback before implementing it. If this PR needs a branch
update, integrate `main` as described above and check each decision against the resulting code
before editing. Add appropriate regressions for accepted behavior fixes. For substantial repairs,
use a fresh read-only review of the repair diff and fix defects introduced by those repairs.
Keep the review scoped to accepted feedback.

Immediately before staging and committing, recheck the original branch, HEAD, and diff for
concurrent user changes. Stage only the intended repair paths. If the branch, HEAD, or unrelated
work changed, stop that repair and record the blocker. Commit successful repairs locally **in the
matched original worktree on its existing branch**. Record the starting and ending local heads,
commit SHAs, addressed comment IDs/URLs, changed behavior, and verification. Leave failed or
unverified repairs explicitly incomplete; do not count them as addressed merely because a patch
exists. A disagreement does not block implementing another independent agreed request.

## Verify And Push

Checkpoint each newly created merge or repair commit immediately, including the exact local head,
observed published head, branch, and worktree. In that PR's checkpoint, record the prepared head
repository, declared base ref, and exact base SHA used to prepare the commit. Record the selected
`main` SHA for a stack update. Mark verification pending and leave the update blocked until its
checks succeed. On retry, compare those saved inputs with freshly verified PR and base metadata.
If an input changed, reassess and integrate against the current inputs before publishing. Recheck
that any agreed repair still applies. For an isolated merge, verify the saved worktree and commit
directly. Use `authored-worktree` for the original worktree.

Run focused project checks on the final tree, including a merge that has no feedback repair.
Record the passing checks against the exact commit before pushing. Combine a merge and agreed
repairs when they can be verified together. Verify any resumed repair again on the updated branch.

Before pushing, refresh author-checked discovery and use body-free metadata to verify that the PR
is open and the published head, head branch, head repository, and declared base ref still match.
For a stacked update, also verify that the base branch head matches the one used to prepare it.
Reassess any changed feedback that affects a repair. Enumerate the effective push URLs with
`git remote get-url --push --all <remote>` and require a single destination for the verified head
repository. Verify that the tested commit descends from the published head and contains only the
intended update. Bind the push to the exact published SHA with an explicit lease so Git rejects
deletion or rewinding of the branch between verification and push. Publish only the existing PR
branch, with implicit tag, mirror, and submodule pushes disabled:

```bash
git -c "remote.$verified_remote.mirror=false" push --no-follow-tags --recurse-submodules=no \
  "--force-with-lease=refs/heads/$verified_branch:$published_sha" \
  "$verified_remote" "$tested_commit:refs/heads/$verified_branch"
```

Never push directly to `main`.

Confirm the new head on GitHub before reporting publication. Record the exact before and after
heads, the merged base SHA, and the checks. If a retry finds the verified commit already published
on the recorded branch and repository, record that result with the saved checks. If a push fails,
retain the verified commit and report the specific blocker. A concurrent head change calls for
fresh integration and verification.
Respect requests to keep work local. The push step leaves GitHub discussion, review threads,
draft state, and the PR's actual merge to the maintainer.

If a contributor PR depends on an authored branch, record the exact dependency head and whether
it was published. Keep contributor experiments in their own isolated worktrees.

## Report And State

Follow [report-format.md](report-format.md). Keep `Your PRs With Review Feedback` in the main
brief, with concise current actions and grouped routine approvals. Summarize authored branch
updates there, including PRs without feedback, and preserve reviewer-owned Done cells. Surface
blocked updates, disagreements, draft holds, and unpublished repairs.

Write complete per-request assessments in the path printed as `authoredFeedbackReportPath` by
begin-run (`reports/<runId>/authored-feedback.md`). Use stable `authored-pr-N` anchors. Include URLs,
draft/published/local heads, original worktree or blocker, each request’s decision and rationale,
local or pushed commits and verification, and remaining work. Carry still-valid decisions into this
current-run record, reading lastDetailReport (or legacy lastReport) only after fresh trust checks
permit reuse. The main briefing links here instead of repeating request history, exact SHAs,
worktree errors and approval-only verification paragraphs for every PR.

Maintain these fields separately from contributor review state in
`state.json.authoredPrFeedback["<number>"]`: `fingerprint`, `lastRemoteHeadSha`, `lastLocalHeadSha`,
`worktree`, `branch`, `lastAssessedAt`, `lastReport`, `lastDetailReport`, `status`, and `decisions`.
`lastReport` identifies the main briefing; `lastDetailReport` identifies the complete per-request
record. Each decision records the stable comment/review ID, observed update time, assessment,
rationale, and local commit if any.
Use `assessed`, `blocked`, or `defer` as the PR status. An assessed disagreement is a completed
assessment. Unfinished agreed work remains blocked. After publication, record the verified remote
head and keep the latest assessed feedback fingerprint. Advance it only after verifying current
feedback. Report merge-only updates even when there is no feedback assessment.
