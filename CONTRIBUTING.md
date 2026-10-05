# Contributing

Keep contributions small, testable, local-only, and privacy preserving. Run JVM
tests, lint, and both debug and release assembly before submitting a change.

Do not commit real ring keys, device identifiers, raw packet captures, btsnoop logs,
health databases, personal health exports, diagnostic reports, screenshots with
health data, signing material, APKs, or local tool configuration. Tests must use
clearly documented public or synthetic fixtures. If provenance is uncertain, do not
submit the fixture.

New Health Connect writes require evidence for units, timestamps, semantics,
deduplication, quality handling, and physical validation. Experimental metrics must
remain read-only until those gates are met. Do not add network access or telemetry
without an explicit design/security review and conspicuous documentation.

Preserve third-party notices and license headers. Identify any copied or adapted
code in `THIRD_PARTY_NOTICES.md` with its exact source and revision.
