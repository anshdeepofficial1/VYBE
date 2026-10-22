# Security Policy / सुरक्षा नीति

Thanks for helping keep VYBE and the people who use it safe. This document explains what is supported, how to report a security issue, and what happens after you report it.

A security report is anything where someone could be harmed by VYBE behaving differently from what the app promises (exposure of personal data, remote code execution, unsafe file handling, unexpected network access, credential leakage, or misleading update/install behaviour).

## Supported versions

Only the **latest published release** receives security fixes. VYBE ships frequently, so older builds are updated very quickly:

| Version                | Supported |
| ---------------------- | --------- |
| latest release (0.14.x) | ✅        |
| older releases          | ❌ — please update first |

If you are unsure about your installed version, the app shows it at the bottom of **Settings → About**.

## How to report

Use **GitHub's private vulnerability reporting**: open this repository's **Security** tab and choose **"Report a vulnerability"**. It stays private — only the maintainer can see your draft until a fix is ready.

If private reporting is unavailable for any reason, open a regular issue with the words **"[security]" in the title**, but **do not paste exploit details, request dumps or account identifiers there** — say only that you found an issue, so a private channel can be arranged.

Please include:

- VYBE version (Settings → About) and Android version
- What you expected to happen and what actually happened
- Steps to reproduce, or a short recording if behaviour differs visually
- Logcat output for crashes or playback problems (Settings → About → copy logs, if available)

What is **not** a vulnerability (please file a normal bug instead):

- Playback or metadata quality problems (wrong artwork, missing related songs)
- Content unavailable because an upstream provider changed
- Issues on devices below the minimum supported Android version
- Behaviour of a repackaged, decompiled or otherwise modified APK

## What happens next

1. **Acknowledgement** within a few days of the report.
2. **Triage** — the report is confirmed, or a follow-up question is asked. Please keep the details private until a fix ships.
3. **Fix and release** — fixes are built into the next release; if the issue is sensitive, the release note only says a security fix landed and details are disclosed after the update reaches users.
4. **Credit** — with permission, the fix release thanks the reporter by GitHub handle. Anonymous reports are equally welcome.

## Handling of user data

- VYBE does not run an account backend; audio library data and app preferences stay on the device and in the cloud accounts the user signs in with directly (for example YouTube Music).
- Crash logs and playback diagnostics are generated locally and are only ever shared when a user deliberately attaches them to a report.
- Update checks (in-app notifications that a new build exists) only contact the project's own public endpoints for the update manifest; no device fingerprinting is involved.

## Safe harbour

Good-faith research, testing and disclosure coordinated through the channels above are always welcome. Please avoid automated scanning that could degrade the service for real listeners, and do not test anything that would cost other users data or money.

## Dependency and supply-chain review

- Dependency freshness: `dependabot.yml` (configuration lives in `.github/`).
- Automated static analysis of app code: CodeQL (`workflows/codeql.yml`).
- Release builds are signed by the project keystore; if an APK you installed from outside was not distributed by this repository's releases, verify it against the release notes published with each build.
