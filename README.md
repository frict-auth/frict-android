# frict-android

The Frict approver app: push 2FA for Authentik. Server, protocol and plan:
[frict-server](https://github.com/frict-auth/frict-server) (`docs/PLAN.md`,
`docs/device-protocol.md`).

## Build and test

Needs JDK 21 and an Android SDK in `ANDROID_HOME`.

```
./gradlew assembleDebug testDebugUnitTest lintDebug
scripts/emulator.sh   # headless emulator (KVM): instrumented tests and the enrollment e2e
```

The emulator gets a PIN and an enrolled fingerprint first: Keystore creates
the `approval_key` only once a strong biometric is enrolled. The enrollment
e2e (`scripts/enroll-e2e.sh`, needs docker) runs frict-server at a pinned
commit behind TLS from a throwaway CA on `127.0.0.1:443`, which the emulator
reaches as `10.0.2.2`. Only the debug build trusts user-installed CAs, which is
how the emulator trusts that one.

## Enrollment

The app enrolls from an activation link
(`frict://enroll?code=…&host=…&user_id=…`, what Authentik's QR code encodes),
opened as a link or pasted. It creates the `device_key` and `approval_key`
with the activation code as attestation challenge, sends the enroll request
signed with the `device_key`, and saves the user once the server accepts it.
Until push lands, it sends the placeholder FID `no-fid-yet`.

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
