# Changelog

## [3.2.1]

### Free vs Pro
- Inline ghost completion (Tab to accept) is now free for everyone.

### Improved
- Database and service URLs with a password inside (`DATABASE_URL=postgres://user:pass@host/db`, `REDIS_URL`, `MONGODB_URI`) are now recognised as secrets. The terminal and Run console mask only the password, so the host stays readable.
- Multi-line values (PEM private keys, certificates, JSON) are read correctly everywhere, and each line of a private key is masked in the terminal.
- `KEY=value # note`: inline comments are no longer treated as part of the value, so these secrets are masked too.
- Common words such as `postgres` or `admin` used as dev passwords are still flagged in the editor but no longer masked in the terminal, so commands and image names stay readable.
- Secrets in fast, continuous terminal output (logs, `docker compose up`) are masked while it streams, not only when output pauses.
- A secret split across two chunks of Run console output is still masked.
- Fewer false alarms: names like `AUTHOR` or `KEYBOARD_LAYOUT`, public values (`NEXT_PUBLIC_*`, publishable and anon keys) and `${VARIABLE}` references are no longer reported as secrets. More token formats are recognised (Anthropic, Google, Hugging Face, npm, GitHub fine-grained).
- Secret warnings use the level set in Settings | Editor | Inspections (warning by default) instead of a hard-coded red error.
- No "not gitignored" warnings in projects without version control, and `.env` files that hold shared defaults next to a `.env.local` (Symfony, Next.js) only report real credential formats.
- A `.env` file that git already tracks now says so and offers "Stop tracking in git" instead of adding a duplicate .gitignore line that changes nothing.
- "Remove duplicate" now removes the earlier definition that is overridden, keeping the value that actually takes effect, and removes multi-line values completely.
- Terminal masking, completion and the settings banner also work when JetBrains' built-in .env plugin owns `.env` files.
- A one-time note explains that terminal masking needs the default terminal engine if you switched to Classic.
- `sops` installed with Homebrew is found on macOS.
- When EnvY hits an error you can send the report to the developer from the IDE's error dialog.

### Fixed
- Copy keeps working after EnvY is disabled, updated or uninstalled without restarting the IDE. Clipboard redaction now hooks in through the IDE's standard extension point.
- The Ctrl+Alt+Shift+X toggle only reacts inside a censored terminal and follows your keymap. It no longer swallows the shortcut everywhere else or toggles the wrong project when several are open.
- SOPS: saving no longer rewrites encrypted files you didn't change, so they stop showing up as modified in git after every focus switch or build.
- SOPS: edits are written when you close the decrypted editor, the project or the IDE, instead of risking a lost background write.
- SOPS: re-encryption keeps the file's encryption rules (encrypted regex or suffix, Shamir threshold, MAC mode), supports Azure Key Vault and HashiCorp Vault keys, and refuses to write if recipients or rules would change.
- No more "EnvY caused an error" reports while the IDE is indexing a project.
- Fixed a rare crash in the terminal censor when a lot of output arrived at once.
- Starting a trial or buying a license turns on terminal and Run console protection right away, including terminals and consoles that were already open.
- Pro features no longer stay unlocked if the IDE's licensing service never answers.

## [3.2.0]

### Fixed
- Resolved a fatal startup conflict when EnvY is installed alongside the "ru.adelf.idea.dotenv" (.env files) plugin. EnvY previously registered the generic `DotEnv` language ID and file type name — both global keys already used by that plugin — which raised repeated `ImplementationConflictException` errors and left the IDE unusable. EnvY now uses unique `EnvY-DotEnv` identifiers so both plugins can be installed together.

## [3.1.0]

### Free vs Pro
- Secret leak detection, gitignore verification, and sensitive key-name detection are now **free** for everyone. Active protection — terminal/console/clipboard secret censoring, SOPS, and inline ghost completion — remains Pro.

### Secret Leak Detection
- Reduced false positives: keys that configure a credential rather than hold one (e.g. `*_ENABLED`, `*_TTL`, `*_TIMEOUT`, `*_TYPE`, `*_ALGORITHM`, `*_PATH`, `*_FILE`) and non-secret value shapes (booleans, plain numbers, durations like `30s`) are no longer flagged. High-confidence pattern matches are unaffected.

### Notifications
- Added an optional, dismissible "leave a review" prompt, shown at most once after EnvY has caught a real issue.
- Added a Pro upgrade prompt shown later for unlicensed users only — capped, on a cooldown, and permanently silenceable. Neither prompt blocks the editor, and both can be muted in Settings | Notifications.

### Docs
- Corrected an inaccurate heap-dump safety claim in the 3.0.0 notes.

## [3.0.0]

### SOPS Integration
- **Real-time Split View:** Seamlessly edit SOPS-encrypted `.env` files with a side-by-side view showing raw ciphertext and editable plaintext.
- **Enterprise Key Management:** Full support for AWS profiles, IAM assumed roles, and KMS ARNs.
- **In-Memory Security:** Decryption and re-encryption happen entirely in-memory—no unencrypted temporary files ever touch your disk.
- **Improved Stability:** Resolved IDE-wide exceptions during decryption and fixed re-encryption failures by automatically extracting target keys from metadata.

### Terminal & Console Security
- **Terminal Secret Censor:** Automatically masks detected secrets in your terminal output. Press `Ctrl+Alt+Shift+X` to instantly toggle visibility.
- **Console Redaction:** Real-time redaction of sensitive environment variables in Run/Debug console logs and stack traces. Now a PRO feature.

### Pricing Update
To support the ongoing development of security features like our new SOPS integration and real-time terminal redaction we are adjusting our subscription pricing for new licenses to $4.00/month for individuals and $10.00/month for organizations. This shift allows us to maintain the high standard of security, performance, and stability that professional engineering teams require from EnvY. **Existing active subscribers are grandfathered into their current rates.**

### Performance & Stability
- **Zero-Allocation Reads:** Refactored inspections to use `charsSequence`, eliminating massive string allocations and GC spikes on large files.
- **UI Responsiveness:** Moved SOPS encryption detection to background threads with non-blocking VFS reads to prevent UI micro-stutters.
- **Memory Management:** Implemented automatic cleanup of cached sessions when files are deleted or moved to prevent memory leaks.
- **Architectural Cleanup:** Migrated to modern JetBrains APIs (BulkFileListener, FileDocumentManagerListener.TOPIC) for better compatibility with 2025.x IDEs.

### Secret Leak Inspection
- Unified Gitignore verification with secret scanning.
- Removed redundant static caches to reduce memory footprint and avoid keeping duplicate copies of parsed values across components.

### Env File Service
- Implemented CachedValuesManager and debounced keystrokes to eliminate GC churn and keep typing buttery smooth.

### License Checker
- Pro feature initialization now properly awaits the background license check, preventing UI flickering and broken startup states for Editor notifications.
- Added tri-state verification to fail-open gracefully during IDE startup, fixing the trial lockout race condition.

### Env Diff Tool
- Tied row colors directly to the table model rather than a separate list, preventing visual bugs when users sort the table.

## [2.4.1]
- Small optimizations
- Stress tests with 1000+ ENV variables
- Live dynamic load test

## [2.4.0]
### Dynamic Plugin Loading
- Plugin can now be enabled, disabled, and updated without restarting the IDE
### 1k Downloads Gift
- Standard code completion has become a free feature to celebrate the first 1k downloads!
### Licensing
- Final license checking integrating the default JetBrains template with async execution
### Diff Tool UI Bug Fix

## [2.3.1]
### UI & Behavior
- **Independent Column Resizing:** Changed the `EnvDiffToolWindow` resize behavior so adjusting the width of one column no longer forcefully resizes the others.
- **Optimistic UI Initialization:** The plugin now optimistically initializes Pro features (like `SecretLeakChecker`) instantly on startup. This completely prevents UI blocking and flickering while the asynchronous license validation completes in the background.

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