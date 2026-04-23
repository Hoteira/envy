# Changelog

## [2.5.0]
### Secret Leak Inspection
Removed static caches to eliminate memory bloat and prevent plaintext secrets from leaking into JVM heap dumps.

### Env File Service
Implemented CachedValuesManager and debounced keystrokes to eliminate GC churn and keep typing buttery smooth.

### License Checker
Added tri-state verification to fail-open gracefully during IDE startup, fixing the trial lockout race condition.

### Env Diff Tool
Tied row colors directly to the table model rather than a separate list, preventing visual bugs when users sort the table.


## [2.4.1]
- Small optimizations
- Stress tests with 1000+ ENV variables
- Live dynamic load test

## [2.4.0]
### Dynamic Plugin Loading
- Plugin can now be enabled, disabled, and updated without restarting the IDE
### 1k Downloads Gift
- Standard code completion has become a free feature to celebrate teh first 1k downloads!
### Licensing
- Final license checking integrating the default JetBrains template with async execution
### Diff Tool UI Bug Fix

## [2.3.1]
### UI & Behavior
- **Independent Column Resizing:** Changed the `EnvDiffToolWindow` resize behavior so adjusting the width of one column no longer forcefully resizes the others.
- **Optimistic UI Initialization:** The plugin now optimistically initializes Pro features (like `SecretLeakChecker`) instantly on startup. This completely prevents UI blocking and flickering while
the asynchronous license validation completes in the background.

## [2.3.0]
### Performance & UX
- Drastically reduced typing latency in large `.env` files by implementing LRU caching for Regex evaluations and `isSecret` checks.
- Eliminated severe UI thread freezes in the `EnvDiffToolWindow` by batching table model additions and executing a single UI repaint.
- Fully decoupled asynchronous cryptography in `LicenseChecker` to prevent 300ms+ thread-blocking freezes on the user's first keystroke.
- Implemented `RangeMarker` tracking in `PresentationModeState` to prevent manually revealed secrets from accidentally re-collapsing when surrounding text shifts.
- Overhauled `EnvFileService` to read directly from the live `FileDocumentManager`, guaranteeing that newly typed, unsaved environment variables appear in code completion instantly.
- Re-architected `EnvParser` to consume raw `CharSequence`, avoiding destructive line splitting and string allocations entirely.
- Decoupled `EnvFileService` caching to rebuild incrementally per file on pooled background threads, completely eliminating synchronous disk reads and O(N) global cache rebuilds on every keystroke.
- Greatly reduced Garbage Collection (GC) pauses by replacing string-allocating path splits with zero-allocation substring checks.
- Prevented over-aggressive file index queries by differentiating VFS events to appropriately scope cache invalidations.
- Avoided massive string allocations and GC spikes during duplicate key fixes by reading directly from `document.charsSequence` instead of `document.text`.
- Resolved "ghost completion" of deleted files and ignored `.gitignore` deletions by updating VFS event listeners to evaluate paths instead of nullified virtual files.

### Fixed
- Extracted shared `EnvParser` utility — eliminates triplicated parsing logic across inspections and services
- Fixed key offset calculation in inspections (no longer relies on fragile `indexOf`)
- Lexer now supports multiline double-quoted values and backtick-quoted values
- Inline completion suggests shortest matching key first instead of purely alphabetical
- Cached `findEnvFiles()` result (2s TTL) to avoid index queries on every keystroke
- Fixed `RemoveDuplicateFix` not handling `\r\n` line endings
- Added error boundary in `GitignoreListener` to prevent crashes on VFS events
- Fixed filename casing typo (`DotENvSyntaxHighlighter.kt` → `DotEnvSyntaxHighlighter.kt`)

## [2.2.1]
### Fixed
- Fixed unbounded parse cache causing potential memory growth in long sessions
- Fixed placeholder detection for case-insensitive values (e.g. "TODO")
- Added diagnostic logging for license validation errors

## [2.2.0]
### Added
- Secret values fold to `***` in presentation mode (free feature, no gitignore requirement)
- Quick-fix actions to reveal a specific key or all hidden values in the file

### Fixed
- Completion now filters by the typed prefix and masks secret values in the popup
- Diff tool row colors now adapt to light and dark themes
- Lexer bounds check for escape sequences at end of quoted values
- License stamp validator tightened (requires valid base64, unknown eval formats denied)

## [2.1.1]
### Fixed
- License handling fix

## [2.0.1]
### Fixed
- Resolved plugin verifier false positives

## [2.0.0]
### Added
- Support for `.envrc` files — syntax highlighting, parsing, and diffs

## [1.0.1]
### Added
- Freemium licensing — Pro features now require a subscription

## [1.0.0]
### Added
- Secret leak detection (API keys, tokens, credentials)
- Gitignore verification for files containing secrets
- Quick-fix: add file to .gitignore
- Quick-fix: replace secret with placeholder
- Key name heuristic detection for sensitive variables
- Env var autocomplete in code (Ctrl+Space)
- Inline ghost completion for env var names (Tab to accept)

## [0.2.0]
### Added
- Recursive `.env` file discovery in subdirectories
- Quick-fix action to remove duplicate keys

### Changed
- Diff view now shows relative paths for nested `.env` files

## [0.1.0]
### Added
- `.env` file syntax highlighting
- Duplicate key detection with warnings
- Cross-environment diff tool window
