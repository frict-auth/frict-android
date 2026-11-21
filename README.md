# frict-android

The Frict approver app: push 2FA for Authentik. Server, protocol and plan:
[frict-server](https://github.com/frict-auth/frict-server) (`docs/PLAN.md`,
`docs/device-protocol.md`).

## Build and test

Needs JDK 21 and an Android SDK in `ANDROID_HOME`.

```
./gradlew assembleDebug testDebugUnitTest lintDebug
scripts/emulator.sh   # headless emulator (KVM): instrumented tests and the e2e
```

The emulator gets a PIN and an enrolled fingerprint first: Keystore creates
the `approval_key` only once a strong biometric is enrolled. The e2e
(`scripts/e2e.sh`, needs docker) enrolls, approves a login and denies one as
"not me", driving the app and the fingerprint sensor. It runs frict-server at
a pinned commit behind TLS from a throwaway CA on `127.0.0.1:443`, which the
emulator reaches as `10.0.2.2`. Only the debug build trusts user-installed CAs, which is
how the emulator trusts that one.

## Static analysis

The `codeql` CI job runs CodeQL's default Java/Kotlin queries over the
`assembleDebug` build, with dependency verification on, and fails on any
finding. Fix the code; a false positive is suppressed by rule id in
`.github/codeql/codeql-config.yml`, nowhere else.

## Dependencies

Gradle verifies every artifact it resolves, plugins and build tooling
included, against the sha256 in `gradle/verification-metadata.xml`; an
unlisted or mismatching artifact fails the build. `app/gradle.lockfile` pins
the app's runtime and test classpaths, which is what the `vulnerabilities` CI
job scans with OSV-Scanner. Build tooling is left out of the scan: its
advisories are fixed by bumping AGP or Kotlin, not here.
Plugins resolve from Google and Maven Central only: the Gradle Plugin Portal
serves different bytes for some of the same plugin marker POMs, which would
fail verification whenever it answered first.

After changing a dependency, regenerate both files:

```
rm gradle/verification-metadata.xml
./gradlew --write-verification-metadata sha256 --write-locks --refresh-dependencies \
  assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest :app:dependencies
```

The diff of the two files is what the reviewer checks: every new or changed
artifact, with its checksum.

Dependabot proposes Gradle and Actions updates weekly, once a release is 7
days old. It updates `gradle.lockfile` but not the verification metadata, so
its Gradle PRs fail CI until someone runs the command above on the Dependabot
branch and pushes the result. It doesn't update the OSV-Scanner image either
(it skips `docker://` references); bump that by hand.

## Enrollment

The app enrolls from an activation link
(`frict://enroll?code=…&host=…&user_id=…`, what Authentik's QR code encodes),
opened as a link or pasted. It creates the `device_key` and `approval_key`
with the activation code as attestation challenge, sends the enroll request
signed with the `device_key`, and saves the user once the server accepts it.
Until push lands, it sends the placeholder FID `no-fid-yet`.

## Approving a login

While the app is open and enrolled, it polls `pending` at the server's
`poll_interval`, signed with the `device_key`, and shows the oldest pending
challenge's details (user, IP address, Authentik's pushinfo). Approve, Deny
(`cancel`) and Not me (`not_me`) each open a BiometricPrompt that allows only
a strong biometric and is bound to a `Signature` over the `approval_key`;
Keystore lets that key sign only after the prompt succeeds, and only the
answer is signed with it. An answer the server refuses as already answered or
expired is shown as such, not retried.

## Debug keystore

`app/debug.keystore` is committed on purpose and is public: every debug build,
on any machine or CI runner, is signed with the same certificate, so the dev
Firebase API key can be restricted to one SHA-1. It grants nothing a release
build has. `scripts/check-debug-signing.sh` (run in CI) fails if the debug APK
is signed by anything else.

- SHA-1: `39:A8:54:E1:B2:D8:39:B5:96:0C:4D:95:52:B2:7E:9A:18:C0:50:5E`
- SHA-256: `21:6D:CF:50:A3:00:A0:40:6A:2C:A0:14:84:D2:99:70:1C:70:BF:FA:3C:2C:66:6C:6C:60:44:3C:F1:E8:59:BB`

```
keytool -list -v -keystore app/debug.keystore -storepass android
```

## Signing vectors

`app/src/test/resources/signing-vectors.json` is made by frict-server's own
signing and verification code, so the unit tests prove both sides build the
same signed bytes and `Authorization` header. After a protocol change,
regenerate it (needs docker):

```
scripts/gen-signing-vectors.sh ../frict-server
```
