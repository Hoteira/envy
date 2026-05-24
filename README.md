<div align="center">
  <img src="src/main/resources/META-INF/pluginIcon.svg" alt="EnvY Logo" width="120" height="120">

# EnvY

**Smart .env file manager for JetBrains IDEs**

[![JetBrains](https://img.shields.io/badge/JetBrains-Marketplace-000000.svg?style=flat-square&logo=jetbrains)](https://plugins.jetbrains.com/plugin/31217-envy/)
[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF.svg?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![License](https://img.shields.io/badge/license-Apache_2.0-blue.svg?style=flat-square)](LICENSE)

<sub> Syntax Highlighting &bull; Duplicate Detection &bull; Secret Leak Protection &bull; Cross-Environment Diff &bull; .envrc Support</sub>

</div>

<br>

## Overview

EnvY brings first-class .env file support to all JetBrains IDEs. Syntax highlighting, duplicate key detection with inline warnings, secret leak protection, a cross-environment diff tool to spot mismatches between environments, and automatic secret folding in presentation mode.

Works with IntelliJ IDEA, WebStorm, PyCharm, CLion, RustRover, GoLand, PhpStorm, and Rider.

<div align="left" style="padding-left: 16px;">
  <a href="https://plugins.jetbrains.com/plugin/31217-envy/pricing">
    <img src="src/main/resources/META-INF/freeTrialButton.svg" alt="Free Trial" height="32">
  </a>
  &nbsp;&nbsp;
  <a href="https://plugins.jetbrains.com/plugin/31217-envy/reviews">
    <img src="src/main/resources/META-INF/reviewButton.svg" alt="Leave a review" height="32">
  </a>
</div>

## Features

### Syntax Highlighting
Full syntax highlighting for .env files — keys, values, comments, quoted strings, and export prefixes are all visually distinct. Follows your IDE color scheme automatically.

### Duplicate Key Detection
Flags duplicate keys with inline warnings. When the same variable appears twice, only the last value takes effect — EnvY catches this before it causes issues in production.

### Cross-Environment Diff
A dedicated tool window for comparing environment files side by side. Select any two .env files and instantly see missing variables, value mismatches, and matching entries.

### Secret Leak Protection <img src="https://img.shields.io/badge/PRO-087CFA.svg?style=flat" height="22" align="absmiddle">
Detects hardcoded secrets — AWS keys, Stripe keys, GitHub tokens, JWTs, and more — with regex pattern matching and key name heuristics. Warns when .env files containing secrets are not gitignored.

### Presentation Mode
Secret values automatically fold to *** when presentation mode is enabled. Use the quick-fix gutter actions to reveal individual keys or all values at once.

### .envrc / direnv Support
Full support for .envrc files used by direnv. Environment variables defined with export are parsed and included automatically.

### Env Var Autocomplete 
Context-aware autocomplete for environment variable access patterns across 10+ languages including low-latency inline ghost completion (Tab to accept).

### Terminal Secret Censor <img src="https://img.shields.io/badge/PRO-087CFA.svg?style=flat" height="22" align="absmiddle">
Hides values from your .env files in the integrated terminal as soon as they appear in output. Censoring is incremental and chunk-aware.

### Clipboard Redaction <img src="https://img.shields.io/badge/PRO-087CFA.svg?style=flat" height="22" align="absmiddle">
Copying a selection that overlaps a censored span puts *** on the clipboard instead of the secret.

### SOPS Encrypted File Support <img src="https://img.shields.io/badge/PRO-087CFA.svg?style=flat" height="22" align="absmiddle">
Seamlessly edit SOPS-encrypted .env files with in-memory decryption/re-encryption. Plaintext never touches disk.

## Free vs Pro

| Feature | Free | Pro |
|---|:----:|:---:|
| Syntax highlighting for .env files |  ✓   | ✓ |
| Duplicate key detection + quick-fix |  ✓   | ✓ |
| Cross-environment diff tool window |  ✓   | ✓ |
| Recursive .env file discovery |  ✓   | ✓ |
| .envrc (direnv) file support |  ✓   | ✓ |
| Secret values hidden in presentation mode |  ✓   | ✓ |
| Quick-fix: reveal hidden key / reveal all |  ✓   | ✓ |
| Env var autocomplete in code |  ✓   | ✓ |
| Terminal secret censoring |      | ✓ |
| Toggle secret visibility (Ctrl+Alt+Shift+X) |      | ✓ |
| Clipboard redaction on copy |      | ✓ |
| Run/Debug console secret redaction |      | ✓ |
| Secret leak detection |      | ✓ |
| Gitignore verification for secrets |      | ✓ |
| Sensitive key name detection |      | ✓ |
| Inline ghost completion (Tab to accept) |      | ✓ |
| SOPS encrypted .env file support |      | ✓ |

## Installation

**From JetBrains Marketplace:**
`Settings` → `Plugins` → `Marketplace` → Search for **"EnvY"** → `Install`

## License

This project is licensed under the Apache 2.0 License - see the [LICENSE](LICENSE) file for details.
