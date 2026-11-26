# frict-android

The Frict approver app: push 2FA for Authentik. Server, protocol and plan:
[frict-server](https://github.com/frict-auth/frict-server) (`docs/PLAN.md`,
`docs/device-protocol.md`).

## Privacy policy

The policy is at <https://privacy.frict.app/>. "Privacy policy", at the bottom
of every screen, opens it in the browser. A fork sets its own in
`PRIVACY_POLICY_URL` in `app/build.gradle.kts` (and in `PrivacyPolicyUrlTest`).

## Build and test

Needs JDK 21 and an Android SDK in `ANDROID_HOME`.

```
./gradlew assembleDebug testDebugUnitTest lintDebug
scripts/emulator.sh   # headless emulator (KVM): instrumented tests and the e2e
```

The emulator gets a PIN, and the e2e (`scripts/e2e.sh`, needs docker) runs
twice. `screen-lock`, before any fingerprint exists, enrolls with the default
unlock by scanning frict-server's QR code with the emulator's back camera
(`-camera-back imagefile:`) and approves a login by typing the PIN. With the
TLS front answering 426 `app_update_required`, the app must show "Update
Frict", which opens its Play listing. Then it removes the screen lock, which
makes Keystore delete the `approval_key`; the app must say so and offer only
"Remove this account", which it does first with the server unreachable, where
the app must offer "Remove anyway", then for real, after which the server must
refuse `/auth` for the user. Before the scan it refuses the camera once and shows a QR code that is
not an activation link; the camera must close when the app leaves the screen
and once the link is scanned. Then a fingerprint is enrolled,
the instrumented tests run, and `fingerprint` enrolls two users of the same
server: the first with "Fingerprint only", then, after the first user's link
is refused as already there, an admin account with "Fingerprint or screen
lock". It approves an admin login from a push handed to firebase-messaging's
receiver, as Play services delivers it (the notification must be the generic
"Sign-in request", naming neither the account nor the sign-in), denies a login of the first user and another as "not me", removes the
first user (the admin must still approve, and the Firebase installation must
stay), then removes the admin anyway with the server unreachable, which must
delete the Firebase installation. Both check that the server holds the app's
Firebase Installation ID for every account after enrollment and again after a
restart. It runs
frict-server at
a pinned commit behind TLS from a throwaway CA on `127.0.0.1:443`, which the
emulator reaches as `10.0.2.2`. Only the debug build trusts user-installed CAs, which is
how the emulator trusts that one.

## Releases

A squash-merged PR titled `feat:` or `fix:` (Conventional Commits) releases:
semantic-release tags the version, and `.github/workflows/release.yml` builds
the AAB signed with the upload key and publishes it to Play's internal track.
Other titles release nothing. Setup, signing and the first upload:
[docs/release.md](docs/release.md).

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

Dependabot proposes Gradle, Actions and npm (semantic-release) updates
monthly, once a release is 7 days old. It updates `gradle.lockfile` but not the verification metadata, so
its Gradle PRs fail CI until someone runs the command above on the Dependabot
branch and pushes the result. It doesn't update the OSV-Scanner image either
(it skips `docker://` references); bump that by hand.

GitHub raises no Dependabot alerts for Gradle, so CI's `vulnerabilities`
(OSV-Scanner on the lockfile) and `codeql` jobs also run on `main` every
Monday at 06:00 UTC.

## Accounts

The app holds several accounts, for example an operator's own and a separate
admin account, on one server or several. Each is an independent enrollment
on its server: its own host, user ID, unlock choice, label, and `device_key`
and `approval_key` under their own Keystore aliases. The main screen lists them
by label with their unlock choice; "Add an account" (or opening an activation
link) enrolls another. A link for a user already on this phone, by host and
user ID, is refused. Every pending request and unlock prompt names the
account it is for by its label; notifications never do. Removing an account, and a lost key,
affect that account only.

The label is set at enrollment: the app suggests `<username> @ <hostname>`,
from the username in the server's enroll reply and the link's host, and the
person enrolling can change it. Left empty it is the suggestion; it is cut to
64 characters. It stays on the phone. An account enrolled before labels shows
its hostname and the start of its user ID until it is enrolled again.

An enrollment saved by an app version with a single account becomes the first
account on the first start, its keys kept where they are.

## Enrollment

The app enrolls from an activation link
(`frict://enroll?code=…&host=…&user_id=…`, what Authentik's QR code encodes),
scanned, opened as a link or pasted.

"Scan QR code" asks for the camera permission when tapped; refused, the screen
says to paste or open the link instead. The scanner reads QR codes only
(ZXing's `QRCodeReader` on CameraX frames) and accepts a code only if it is an
activation link that passes the same check as a pasted one; anything else is
shown as rejected and never opened. The camera stops at the first activation
link, or when the scanner or the app leaves the screen. Frames stay in memory
and nothing goes over the network. It creates the `device_key` and `approval_key`
with the activation code as attestation challenge, sends the enroll request
signed with the `device_key` with its Firebase Installation ID (FID), and saves
the account once the server accepts it, or deletes the new keys. A build without a Firebase project sends
the placeholder FID `no-fid` and polls.

Before enrolling, the user picks what unlocks the `approval_key`:

| Unlock | Key (`setUserAuthenticationParameters(0, …)`) | Needs |
| --- | --- | --- |
| Fingerprint or screen lock (default) | `AUTH_BIOMETRIC_STRONG \| AUTH_DEVICE_CREDENTIAL` | a secure screen lock |
| Fingerprint only | `AUTH_BIOMETRIC_STRONG` | a secure screen lock and an enrolled strong biometric |

Keystore fixes a key's unlock when it creates it, so the choice is saved with
the account (not a secret) and shown on the main screen; changing it means
removing the account and enrolling again, with a new activation code.

"Remove this account", after a confirmation naming the account, sends
`/device/v1/remove` signed with its `device_key`, which deletes the enrollment
on the server. On `200`, or `401` (already gone), it deletes the saved account,
then its two keys (kept if the account can't be deleted). If the server can't
be reached or fails, the app says so and offers "Remove anyway", which removes
the account here only. Once the last account is gone, the app deletes its
Firebase installation (`FirebaseInstallations.delete()`), so Firebase drops the
FID, and returns to the enrollment screen. An account saved before the choice
existed shows "Fingerprint only", which is what its key requires.

"Fingerprint" is Android's strong (Class 3) biometric: a Class 3 face unlock
works too. Removing the screen lock invalidates either key (Keystore deletes
it). Adding a fingerprint, or removing them all, invalidates a "Fingerprint
only" key; a "Fingerprint or screen lock" key is bound to the screen lock alone
and survives both.

The app checks the `approval_key` each time it comes to the screen and before
each answer, and the `device_key` on each signed request. When Keystore has
lost either, that account says what happened and offers only "Remove this
account", the same server-first removal. Without the `device_key` the server
can't be told, so that ends in "Remove anyway".

## Minimum app version

Every request sends `Frict-App-Version: <versionCode>`. The header is not part
of the signed bytes: it is an upgrade nudge, not a security control, and a
client can send any number. If the server answers HTTP 426 with
`{"error":"app_update_required","min_version":N}`, the app shows "Update
Frict", whose button opens the app's Play listing, and does nothing else with
that request: no answer is retried, nothing is removed or saved, and no
"Remove anyway" is offered. Any other status is handled as before. A 426 from
any account's server shows the prompt; that server's next successful poll
hides it.

## Push

Only the debug build (`app.frict.android.dev`) has a `google-services.json`,
for the dev Firebase project. A release build fails without
`app/src/release/google-services.json`, which comes from the prod Firebase
project (see [docs/release.md](docs/release.md)). The FID belongs to the app
install, not an account: on every start with an account, the app registers
with FCM and sends its FID to each account's `/device/v1/fid`, signed with that
account's `device_key`; FCM's `onRegistered` sends a new one the same way.

A push carries a challenge ID and the user ID the server issued at
enrollment, two random IDs, and nothing else. The app asks only the server
of the account with that user ID for the challenge, signed with its
`device_key`, and shows what the server returns, never the push's contents.
A push whose user ID matches no account here (or more than one) makes no
request and gets no notification, so no other server learns of the sign-in.
The app shows a high-priority notification for each (channel "Sign-in
requests"), "Sign-in request: Tap to approve or deny", locked or not: no account label,
host, username, IP or application. It is `VISIBILITY_PRIVATE` with a public
version saying the same. Tapping it opens the app on the challenge, which shows
the details. Polling stays: a missed push is found by the next poll. The app
asks for the notification permission once it has an account.

## Approving a login

While the app is open, it polls each account's `pending`, signed with that
account's `device_key`, at the shortest `poll_interval` the servers set, and
shows each account's oldest pending challenge above the account list, with its
details (account, IP address, Authentik's pushinfo). Approve, Deny
(`cancel`) and Not me (`not_me`) each open a BiometricPrompt bound to a
`Signature` over that account's `approval_key`, allowing what the key allows
(`BIOMETRIC_STRONG`, plus `DEVICE_CREDENTIAL` for "Fingerprint or screen
lock", which replaces the Back button: the platform forbids both). Keystore
lets that key sign only after the prompt succeeds, and only the answer is
signed with the prompt's result. An answer the server refuses as already answered or
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
