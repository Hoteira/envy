# Changelog

## [0.1.0]
### Added
- .env file syntax highlighting
- Duplicate key detection with warnings
- Cross-environment diff tool window
- 
## [0.2.0]
### Added
- Recursive .env file discovery in subdirectories
- Quick-fix action to remove duplicate keys
### Changed
- Diff view now shows relative paths for nested .env files

## [1.0.0]
### Added
- Secret leak detection (API keys, tokens, credentials)
- Gitignore verification for files containing secrets
- Quick-fix: add file to .gitignore
- Quick-fix: replace secret with placeholder
- Key name heuristic detection for sensitive variables
- Env var autocomplete in code (Ctrl+Space)
- Inline ghost completion for env var names (Tab to accept)
- 
## [1.0.1]
### Added
- Freemium licensing - Pro features now require a subscription

## [2.0.0]
### Added
- Added support for .envrc files syntax highlighting and diffs

## [2.0.1]
### Added
- Tweaks to stop plugin verifier from flagging randomly

## [2.1.1]
### Added
- License handling fix

## [2.2.0]
### Added
- Secret values fold to `***` in presentation mode (free feature, no gitignore requirement)
- Quick-fix actions to reveal a specific key or all hidden values in the file

### Fixed
- Completion now filters by the typed prefix and masks secret values in the popup
- Diff tool row colors now adapt to light and dark themes
- Lexer bounds check for escape sequences at end of quoted values
- License stamp validator tightened (requires valid base64, unknown eval formats denied)