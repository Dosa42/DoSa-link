# Wireless Workshop delivery workflow

Build APKs, native components and Gradle outputs only through Dosa42/DoSa-link GitHub Actions. Keep one current APK in dist. Reuse the existing JADX engine cache and completed analysis results.

Before publishing, consolidate the source, manifest, packaging assertions and export integration for the requested feature. Run tools/preflight.py locally for source checks; Android compilation runs in CI before packaging. Record the concrete reason for each additional APK build. Do not publish intermediary edits that are still being reviewed together.

Prepare a focused on-device verifier while CI runs. Exercise the feature's success path, relevant permission/error path and lifecycle cleanup. After a failure, rerun that failed check first; repeat passing checks only when a subsequent change affects them. Use a small tool regression when shared startup or persistence changes; do not re-decompile the original eWeLink APK for unrelated features.

Treat Android API availability, process ownership, main-thread blocking and shared SQLite access as integration questions before the first build. Prefer existing components and screen-owned discovery when background operation is outside the requested delivery.

Maintain a short SESSION.md with current source, installed version, last result and next action. Keep one final verification report and update the existing foundation plan. Preserve failures as evidence without copying entire logs into repeated handoffs. Finish documentation and owned scratch cleanup after the acceptance checks pass.

Preserve original modules, APKs, settings, profiles, histories, credentials and signing material. No semantic graph work or direct Codex bridge. Distinguish packaged code, loaded classes, constructed modules, resolved native dependencies, executed JNI and actual gadget operation.

Apply the managed no-code-comments policy to authored or modified code and retain the clean scan receipt.
