# Settings retention layout

## Scope

Addressed browser Comments 5 and 6: prevent helper text from overlapping the cache-retention input, and align “Save retention” to the section’s lower right.

## Changes

- Let the retention form use the full section width so its existing right-aligned action row reaches the section’s right edge.
- Added retention-specific spacing above the helper text to avoid the generic field-note negative margin pulling it into the input row.
- Kept the existing small-screen stacked layout and label/input association.

## Checks

- `git diff --check` passed.
- No build, tests, or browser check were run.
