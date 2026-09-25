<div align="center">
  <img src="docs/icon.png" alt="App Icon" width="100" />
  <h1>RikkaHub Enhanced</h1>

An independent, enhanced fork of [RikkaHub](https://github.com/rikkahub/rikkahub) — a native Android LLM chat client.

[简体中文](README_ZH_CN.md) | [繁體中文](README_ZH_TW.md) | English
</div>

## Overview

RikkaHub Enhanced tracks upstream RikkaHub and adds capabilities for long-running, unattended use.
It ships under its own application ID and version line, so it can be installed alongside the official app.

| | |
|---|---|
| Application ID | `me.rerere.rikkahub.enhanced` |
| Current version | `2.5.2-rhe.1.0` (versionCode `187001`) |
| Upstream baseline | RikkaHub `2.5.2` (versionCode `187`) |
| License | AGPL-3.0 |

## What this fork adds

**Background running.** A persistent, silent notification backed by a foreground service keeps the app alive,
so streaming replies, notifications and long tasks survive leaving the app. A guided setup screen covers
notification permission, battery optimization and OEM auto-start settings.

**Background jobs (`job_*` tools).** The assistant can run long-lived commands inside a workspace sandbox and
manage them afterwards: start, list, status, incremental log reading, wait, stop, restart, delete. Includes
reusable job definitions with parameters, scheduling (delay / interval / cron), an interactive pty mode,
completion notifications, and optional wake-up of the conversation when a job finishes.

**Tool-call auto-approval.** Approvals required by high-impact tools can be delegated to a model of your choice;
anything uncertain still asks you.

**Upstream fix.** A navigation crash (`NavDisplay backstack cannot be empty`) present upstream is fixed.

## Download

Prebuilt APKs are attached to [Releases](https://github.com/BZLZHH/rikkahub/releases) when published.

```bash
adb install -r app-universal-release.apk
```

The application ID differs from the official app, so both can be installed at the same time.
In-place upgrades require the same signing key.

## Build

Requirements: JDK 21, Android SDK (compileSdk 37).

```bash
./gradlew :app:assembleRelease
```

`app/google-services.json` is not part of this repository; supply your own Firebase configuration before
building release variants.

## Versioning

- `versionCode` = upstream versionCode × 1000 + RHE build number
- `versionName` = `<upstream version>-rhe.<RHE version>`

## Relationship to upstream

This is a fork and is neither affiliated with nor endorsed by the RikkaHub project. Upstream code is the
foundation of this repository; all credit for the original work belongs to the RikkaHub authors.
Fork-specific issues should be reported here rather than upstream.

## License

AGPL-3.0. See [LICENSE](LICENSE). As required by the license, the complete corresponding source of this fork
is available in this repository.
