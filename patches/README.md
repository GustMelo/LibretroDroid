# Local changes

The Git history is the source of truth for runtime changes; do not keep a second,
manually synchronized copy of those changes as .patch files. Core-specific patches
live in `native/cores/patches` because cores are fetched at pinned commits.

Use `git diff 8835c3098514390a271e36983957f7bb5f40abf1...HEAD` to inspect runtime changes after fetching tags.
