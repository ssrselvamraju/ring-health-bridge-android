# Public release plan and audit record

Audit date: 2026-10-01

Candidate basis: private source commit `c272247` with the private working-tree
change to `M7_RESEARCH_REPORT.md` deliberately excluded.

## Recommendation

Selected public repository: `ring-health-bridge-android`.
Display name: Ring Health Bridge. Subtitle: "An unofficial local Health Connect
bridge for Oura Ring Gen 3". README carries the explicit Oura Health Oy trademark
and non-affiliation disclosure. User-facing app labels, notification title,
permission rationale, and diagnostic names use Ring Health Bridge.
Existing package identifiers and private-storage paths are retained for compatibility.

The candidate remains based on `c272247`. The private notebook subsequently advanced
to `1137136`; those later trial/report-export changes are not part of this audited
candidate. Original verification results below describe the 2026-10-01 build.
The branded candidate was verified again on 2026-10-05: all 86 JVM tests passed,
lint completed with 40 warnings and no errors, and debug and release assemblies
passed. The release manifest retained no INTERNET permission, disabled backups,
and extraction rules. Final staged filename and credential/identity checks found
no prohibited files or matching sensitive values. These remain documented checks,
not a guarantee that dedicated secret-scanner tools were run.

Keep the existing repository private as the personal development and research
notebook. Publish, if approved, a new repository created from this sanitized source
snapshot with a fresh initial commit and a GitHub noreply author identity.

Do **not** make the existing repository public and do not rewrite its history. Its
history contains personal research chronology and personal commit metadata. Deleting
those files on the tip would not remove earlier blobs, author identities, reflogs,
cached clones, or other copies. A history rewrite would require destructive filtering,
force-pushing, coordination with every clone, and another full audit, while still
offering no benefit over a clean snapshot.

| Option | Privacy risk | Operational risk | Recommendation |
| --- | --- | --- | --- |
| Make the private repository public as-is | Critical: private documents and personal author metadata remain in history | Low effort but irreversible exposure | Reject |
| Rewrite the private repository | High: easy to miss blobs/refs; requires force-push and clone coordination | High; disrupts the notebook and remote history | Reject |
| Fresh repository from this snapshot | Lowest: no inherited objects, refs, remote, or author history | Moderate one-time review and setup | Use this option |

The maintainer created the destination repository and authorized the initial source
push. Publication uses fresh history and a GitHub noreply identity. The private
notebook's visibility and history are unchanged. APK distribution and repository
visibility changes are not part of this authorization.

## Inclusion and exclusion matrix

| Material | Candidate treatment | Reason |
| --- | --- | --- |
| Android source, resources, JVM tests, Gradle wrapper | Included | Required to build and verify the application |
| Desktop provisioning wrappers and pinned-upstream notes | Included, with generic documentation | Required for reproducible developer provisioning; secrets remain local |
| Root/android README | Rewritten | Public behavior, setup, reset consequences, permissions, and limitations |
| License, notices, security, contributing, CI, Dependabot | Added | Public licensing, attribution, reporting, hygiene, and maintenance |
| `PLAN.md`, `STATUS.md` | Omitted | Personal chronology, device observations, dates, and record/battery details |
| `M7_RESEARCH_REPORT.md`, `M7_RESEARCH_HANDOFF.md` | Omitted | Personal health/research chronology and device results; one file also has a private uncommitted change |
| `UI_CAPABILITY_MATRIX.md`, `UI_DECISIONS.md`, `UI_INTEGRATION_CONTRACT.md`, `UI_ROADMAP.md` | Omitted | Internal design notebook material not required to build or use the app |
| `design/app-icon-concept-c.png` | Omitted | Non-build binary with OpenAI/C2PA provenance; public vector launcher asset is disclosed instead |
| `third_party/` checkout | Omitted | Ignored, large, separately licensed upstream source; reproducible pin is documented |
| Private `.git` history and remote | Omitted | Contains personal author identity and private research history |
| Local properties, IDE state, Gradle caches, build output, APKs, signing files | Omitted | Machine-specific or generated; APK publication/signing was not authorized |
| Keys, databases, captures, diagnostics, device identifiers, research exports | Omitted and ignored | Sensitive secrets, health data, or device data |
| `.codex`, `.agents`, attachments, temporary Python bytecode | Omitted | Local assistant/tool state and generated files |

## Privacy and security findings

- The merged release manifest has no `android.permission.INTERNET`. A transitive
  library adds `ACCESS_NETWORK_STATE`, but without `INTERNET` it does not grant
  network communication.
- `android:allowBackup="false"` remains set. `data_extraction_rules.xml` excludes
  every credential-protected and device-protected root, file, database, preferences,
  and external-files domain from both cloud backup and device transfer.
- The release source set's `KeyImportBridge` always returns `DISABLED`; only debug
  builds contain the ADB-staged import implementation.
- Actual write calls and permission constants cover only heart rate, RMSSD HRV, and
  sleep. Unused write permissions for resting heart rate, respiratory rate, oxygen
  saturation, skin temperature, steps, and active calories were removed. Optional
  read-only research permissions remain documented.
- REAL_STEPS remains confirmation-gated, mode-only, and unpublished. The code saves
  the original state, rejects a second enable while rollback is pending, verifies
  rollback, and leaves subscription unchanged (or reports when its original value is
  unavailable).
- Raw history uses the app-private SQLite directory. Ring credentials are wrapped by
  Android Keystore and stored in private preferences. No provider exposes either.
- Static search found no Android logging, `println`, `printStackTrace`, or Timber
  calls in app source. User-triggered diagnostic clipboard output is limited to
  status/count information by design; no raw event-body logging path was found.
- App-owned exported entry points are the launcher, the generic Health Connect
  permission-rationale activity, the platform-required permission-usage alias, and
  the companion-device service. The alias and companion service have platform
  permissions. The foreground sync service is not exported.
- The merged manifest also contains AndroidX components. WorkManager job/diagnostic
  and profile-installer entry points are either unexported or platform-permission
  protected. `HealthDataSdkService` is intentionally exported by
  `androidx.health.connect:connect-client:1.1.0`; it is inherited library surface,
  not an app database/provider component.
- The public snapshot contains no personal name, email address, absolute home path,
  MAC address, APK, database, capture, signing key, private key, or common cloud/GitHub
  credential pattern.

## Secret and history audit

Dedicated `gitleaks` and `trufflehog` executables were unavailable, so this audit
does not claim either tool passed. Independent checks instead covered candidate file
names/content and all 18 private-history commits:

- credential-pattern scan for common AWS/GitHub tokens, bearer tokens, private-key
  headers, and assigned secrets: no match;
- MAC-address scan: no match;
- history object-name scan for APKs, databases, captures, keys, signing files, dumps,
  and health-export formats: no match;
- history blob-size scan: no blob over 1 MiB;
- candidate personal identity/path/email scan: no match;
- private history author scan: confirms the personal author identity, which is one
  reason the history must remain private;
- privacy review: `PLAN.md`, `STATUS.md`, and the M7 documents contain the known
  personal chronology and device/health observations and are excluded;
- long-hex review: remaining candidate values are the pinned upstream commit, Gradle
  source/checksum identifiers, and the upstream authentication known-answer fixture.

The authentication fixture is not treated as a secret. At the pinned upstream
revision, the exact key/nonce/result tuple appears in
`crates/oura-protocol/src/auth.rs` and `crates/oura-link/src/client.rs`, and the key
is documented upstream as a test key. The Android test reproduces that public
known-answer vector; it is not evidence of the user's ring key.

## Licensing, attribution, trademark, and provenance

- The proposed project license is MIT. It is compatible with the pinned upstream
  workspace's `license = "MIT"` declaration.
- The pinned upstream revision is
  `c5106bd5674dd98f07954b96dff9e16c8fe26f06` from
  <https://github.com/Th0rgal/open_oura>.
- That pinned tree has no standalone `LICENSE` file and no explicit copyright line.
  This nuance is recorded in `THIRD_PARTY_NOTICES.md`; no upstream notice was
  removed, and the project's full MIT text is included.
- Adapted protocol/authentication/history/decoder files and fixtures are listed in
  `THIRD_PARTY_NOTICES.md`. Gradle wrapper Apache-2.0 headers are preserved.
- README includes the required unofficial/non-affiliation disclaimer for Oura,
  Google, Samsung, and Fitbit.
- README discloses AI assistance for the retained vector launcher artwork and the
  omission of the C2PA-bearing binary design source.

## Dependency and license review

The resolved release runtime tree contains the declared AndroidX Activity, Compose,
Material 3, Health Connect, Lifecycle, and WorkManager libraries plus Kotlin,
coroutines, Guava, Room/SQLite, and support annotations pulled transitively. Manual
review found permissive Apache-2.0/BSD/MIT-style licensing for the shipped runtime
families; no proprietary model or vendored upstream code is present.

A broader local Gradle metadata review parsed 367 cached POMs (including build and
test tooling, not just shipped runtime). Most declared Apache-2.0; 40 had blank
license names, so POM metadata alone is not a complete SBOM or legal opinion. Before
binary distribution, generate and archive a release-specific SBOM/license report and
have the maintainer perform the final legal review.

## Verification results

Run against the synchronized candidate on 2026-10-01:

- JVM unit tests: **pass**, 86 tests in 24 suites, 0 failures/errors/skips.
- Android lint: **pass with 40 warnings**, 0 errors. Breakdown: 31 `UseKtx`, 5
  dependency-update notices, and one each for the protocol-required AES/ECB call,
  resource shrinking, an obsolete `v26` qualifier, and Gradle version availability.
- Debug APK assembly: **pass**. APK was generated only in the external build-output
  area and is not part of the candidate.
- Release assembly/minification: **pass**. An unsigned release APK was generated only
  in external build output; it was not signed or published.
- Compiler/package warnings: two existing Android API deprecations and inability to
  strip the transitive `libandroidx.graphics.path.so`; neither failed the build.
- Merged release manifest: **pass** for no `INTERNET`, `allowBackup=false`, extraction
  rules present, reduced Health Connect writes, and no app-owned unprotected sensitive
  service/provider/receiver.
- Candidate secret/privacy scan: **pass under the documented regex/object checks**;
  dedicated secret-scanner tools were unavailable.
- Private full-history scan: **not safe to publish**, because private research files
  and the personal author identity are historical even though no common credential,
  key/capture/database object, MAC address, or oversized blob was found.
- Dependency/license review: **pass for source-candidate review with warning** that a
  formal release-specific SBOM and final legal review remain advisable before binary
  distribution.
- Gradle wrapper integrity: **pass**. The local wrapper JAR SHA-256 is
  `55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c`, and the
  pinned 9.4.1 distribution checksum is
  `2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb`; both match
  Gradle's published checksum reference.

## Files changed for the candidate

Modified relative to the private source snapshot:

- `.gitignore`
- `README.md`
- `android/README.md`
- `android/app/src/main/AndroidManifest.xml`
- `desktop/README.md`
- `desktop/SECURITY_REVIEW.md`
- `desktop/UPSTREAM.md`

Added:

- `.github/dependabot.yml`
- `.github/workflows/android.yml`
- `CONTRIBUTING.md`
- `LICENSE`
- `PUBLIC_RELEASE_PLAN.md`
- `SECURITY.md`
- `THIRD_PARTY_NOTICES.md`
- `android/app/src/main/res/xml/data_extraction_rules.xml`

Branding changes additionally affect the app-name string, permission rationale,
clipboard label, notification title, and research diagnostic source name. Generated
Kotlin session state is ignored alongside Gradle caches.

All other included source/test/build-control files are copied from private commit
`c272247`. Its private `PLAN.md` change was not copied. The original private working
tree and its `M7_RESEARCH_REPORT.md` modification were not edited or discarded.

## Go/no-go assessment

- Existing private repository or any repository containing its `.git` directory:
  **NO-GO for public visibility**.
- This source snapshot: **GO for maintainer review and creation of a fresh local Git
  history**.
- Initial source push to the selected new repository: **approved by the maintainer**,
  subject to the final checks below.
- Release or APK distribution: **NO-GO until separate explicit approval**.

## Publication checklist

After reviewing every file in this candidate:

1. Confirm the public repository name, MIT license choice, and GitHub noreply author
   identity.
2. Re-run the candidate scan and all quality gates from a clean checkout.
3. Initialize a new Git repository in a separate directory with `main` as its initial
   branch; do not copy any private `.git` object or remote configuration.
4. Configure the noreply identity only in that new repository, stage the candidate,
   inspect `git diff --cached --stat` and `git diff --cached`, and create one clean
   initial commit.
5. Push the single clean-history branch to the maintainer-created destination using
   the explicit push approval. Never change the private notebook's visibility.
6. Enable private vulnerability reporting, Dependabot, branch protection, and secret
   scanning where available.
7. Do not attach, sign, or publish an APK until a separate release review, SBOM/license
   archive, signing plan, and explicit approval are complete.
