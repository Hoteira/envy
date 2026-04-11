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