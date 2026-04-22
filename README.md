<div align="center">
  <br>
  <img src="src/main/resources/META-INF/pluginIcon.svg" alt="EnvY Logo" width="120" height="120">


**Smart `.env` file manager for JetBrains IDEs**

[![JetBrains](https://img.shields.io/badge/JetBrains-Marketplace-000000.svg?style=flat-square&logo=jetbrains)](https://plugins.jetbrains.com/plugin/31217-envy/)
[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF.svg?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![License](https://img.shields.io/badge/license-Apache_2.0-blue.svg?style=flat-square)](LICENSE)

<sub> Syntax Highlighting &bull; Duplicate Detection &bull; Secret Leak Protection &bull; Cross-Environment Diff &bull; .envrc Support</sub>

</div>

<br>

<!-- Plugin description -->
EnvY brings first-class `.env` file support to all JetBrains IDEs. Syntax highlighting, duplicate key detection with inline warnings, secret leak protection, a cross-environment diff tool to spot mismatches between environments, and automatic secret folding in presentation mode.

## Free vs Pro

| Feature | Free | Pro |
|---|:----:|:---:|
| Syntax highlighting for `.env` files |  ✓   | ✓ |
| Duplicate key detection + quick-fix |  ✓   | ✓ |
| Cross-environment diff tool window |  ✓   | ✓ |
| Recursive `.env` file discovery |  ✓   | ✓ |
| `.envrc` (direnv) file support |  ✓   | ✓ |
| Secret values hidden in presentation mode |  ✓   | ✓ |
| Quick-fix: reveal hidden key / reveal all |  ✓   | ✓ |
| Env var autocomplete in code |  ✓   | ✓ |
| Secret leak detection |      | ✓ |
| Gitignore verification for secrets |      | ✓ |
| Quick-fix: add to `.gitignore` |      | ✓ |
| Quick-fix: replace secret with placeholder |      | ✓ |
| Sensitive key name detection |      | ✓ |
| Inline ghost completion (Tab to accept) |      | ✓ |

Works with IntelliJ IDEA, WebStorm, PyCharm, CLion, RustRover, GoLand, PhpStorm, and Rider.
<!-- Plugin description end -->

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

Full syntax highlighting for `.env` files — keys, values, comments, quoted strings, and `export` prefixes are all visually distinct. Follows your IDE color scheme automatically.

**Supports any `.env.*` variant.**

### Duplicate Key Detection

Flags duplicate keys with inline warnings. When the same variable appears twice, only the last value takes effect — EnvY catches this before it causes issues in production.

### Cross-Environment Diff

A dedicated tool window for comparing environment files side by side. Select any two `.env` files and instantly see:

- **Missing variables** — keys that exist in one file but not the other
- **Value mismatches** — same key, different values across environments
- **Matching entries** — confirmation that configs are synced

### Secret Leak Protection (Pro)

Detects hardcoded secrets — AWS keys, Stripe keys, GitHub tokens, JWTs, and more — with regex pattern matching and key name heuristics. Warns when `.env` files containing secrets are not gitignored, with one-click quick-fixes to add them to `.gitignore` or replace values with placeholders.

### Presentation Mode

Secret values automatically fold to `***` when presentation mode is enabled. No configuration needed — EnvY detects sensitive keys and hides their values. Use the quick-fix gutter actions to reveal individual keys or all values at once.

### .envrc / direnv Support

Full support for `.envrc` files used by direnv. Environment variables defined with `export` are parsed and included automatically.

### Env Var Autocomplete 

Context-aware autocomplete for environment variable access patterns across 10+ languages — JavaScript, Python, Rust, PHP, Ruby, Go, Java, Kotlin, C#, and more. Includes **low-latency inline ghost completion** (Tab to accept) powered by an asynchronous caching engine.

## Installation

**From JetBrains Marketplace:**

`Settings` → `Plugins` → `Marketplace` → Search for **"EnvY"** → `Install`

---

## Usage Guide

EnvY works out of the box with zero configuration required, but here is how you can get the most out of its features:

### Cross-Environment Diff Tool
1. Look for the **"Env Diff"** tool window tab at the bottom - left of your IDE.
2. Click to open it, and use the dropdowns to select any two `.env` files in your project (e.g., `.env.development` and `.env.production`).
3. The panel will instantly populate with missing keys and value mismatches.

### Quick-Fixes (Secret Leaks & Duplicates)
When EnvY detects a **duplicate key** or an **exposed secret** <img src="https://img.shields.io/badge/PRO-087CFA.svg?style=flat" height="22" align="absmiddle">, it will highlight the line.
* Place your cursor on the highlighted text.
* Press `Alt+Enter` (Windows/Linux) or `⌥+Enter` (macOS) to open the context menu.
* Select the desired action (e.g., **"Add to .gitignore"** or **"Replace secret with placeholder"**).

### Ghost Text Autocomplete <img src="https://img.shields.io/badge/PRO-087CFA.svg?style=flat" height="22" align="absmiddle">
Start typing your environment variable accessor (like `process.env.` in JavaScript or `os.getenv()` in Python).
EnvY will automatically suggest variables from your `.env` files using inline gray text.
* Press `Tab` to accept the suggestion.

### Presentation Mode
EnvY automatically folds sensitive values (turning them into `***`) the moment you enter IntelliJ's native Presentation Mode (`View` → `Appearance` → `Enter Presentation Mode`).
* To temporarily reveal a specific folded secret, **left-click** the on the `***`, or press `Alt+Enter` to use the **Reveal** quick-fix.

---

## Troubleshooting & FAQ

**I purchased a Pro trial, but the features aren't unlocking?**
Because EnvY integrates deeply with the JetBrains licensing API, **you must restart your IDE** after starting a trial or purchasing a license for the Pro features (like Ghost Completion and Secret Detection) to fully activate.

**How do I report a bug or request a feature?**
Please open an issue on our [GitHub Issue Tracker](https://github.com/Hoteira/envy/issues).

---

## License

Licensed under the [Apache License 2.0](LICENSE).


