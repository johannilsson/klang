---
name: squash-pr
description: Squash the session branch's commits into one commit for the pull request, with a combined message, and force-push it safely. Use when asked to squash a PR.
---

# Squash the branch

Only when the user asks. It rewrites the branch's history, so go in this order and check each step.

1. `git status --short` must be empty and everything pushed. `git fetch origin main`, then
   `base=$(git merge-base HEAD origin/main)`. If `origin/main` has moved past `base`, tell the user (a rebase is a
   different job).
2. Back up: `git branch -f backup-before-squash HEAD`, and note `git rev-parse HEAD^{tree}`.
3. Write the combined message to a file in the scratchpad: a title, `Closes #N` lines for the tickets the PR
   closes (list the open issues to be sure), then short sections by area. No `Co-Authored-By` or session trailer
   if the user asked to remove them. Do not write test counts.
4. `git reset --soft "$base"`, then commit with `-F` the message file. If the user wants a different author,
   set `GIT_AUTHOR_NAME/EMAIL` and the committer variables for that one command and say which you used.
5. The tree hash must equal the one from step 2; if not, `git reset --hard backup-before-squash` and stop.
6. `git push --force-with-lease=<branch>:<old head sha> origin <branch>`. Then check the remote head equals the
   local one and the PR shows one commit.
7. Update the PR description if it mentions "separate commits", and confirm each `Closes #N` ticket lists the PR
   as closing it. Do not close tickets by hand and do not merge.
