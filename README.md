# frict-android

The Frict approver app: push 2FA for Authentik. Server, protocol and plan:
[frict-server](https://github.com/frict-auth/frict-server) (`docs/PLAN.md`,
`docs/device-protocol.md`).

## Build and test

Needs JDK 21 and an Android SDK in `ANDROID_HOME`.

```
./gradlew assembleDebug testDebugUnitTest lintDebug
scripts/emulator.sh   # headless emulator (KVM), instrumented tests, then stops it
```

## Signing vectors

`app/src/test/resources/signing-vectors.json` is made by frict-server's own
signing and verification code, so the unit tests prove both sides build the
same signed bytes and `Authorization` header. After a protocol change,
regenerate it (needs docker):

```
scripts/gen-signing-vectors.sh ../frict-server
```
