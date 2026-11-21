# Releases

`.github/workflows/release.yml` runs on every push to `main`:

1. `version`: semantic-release reads the commits since the last `v*` tag. A
   `feat:` makes a minor release, a `fix:` or `perf:` a patch, a
   `BREAKING CHANGE:` footer a major one. It tags `vX.Y.Z` and creates the
   GitHub release. Otherwise nothing more runs.
2. `bundle` (environment `release`): builds `bundleRelease` with
   `VERSION=X.Y.Z` (versionName `X.Y.Z`, versionCode `X*1000000 + Y*1000 + Z`),
   signs it with the upload key and runs `scripts/check-release-signing.sh`,
   which fails unless the AAB is signed by that key and the key isn't
   `app/debug.keystore`'s. Uploads the AAB and R8 mapping as `release-aab`.
3. `play` (environment `release`, the only job with `id-token: write`):
   impersonates `frict-play-release` through keyless WIF and publishes to the
   internal track.

The release build is `app.frict.android`: R8 and resource shrinking on, no
debug keystore, none of the debug manifest's local-network permission or
user-CA trust. It fails without:

- `app/src/release/google-services.json`, from the prod Firebase project.
  `.gitignore` ignores `google-services.json`, so commit it with `git add -f`.
- The four secrets of the `release` environment. Jobs from any branch but
  `main` can't use that environment, and nothing in this workflow runs on
  `pull_request`.

## Setup (Geoffrey, on the desktop)

### 1. Upload key

```
keytool -genkeypair -v -keystore frict-upload.jks -storetype PKCS12 \
  -alias upload -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Frict upload key, O=frict-auth"
```

keytool asks for the password. PKCS12 has one password for the store and the
key: `KEYSTORE_PASSWORD` and `KEY_PASSWORD` are both it. Keep `frict-upload.jks`
and the password in the password manager; never in a repo. A lost or leaked
upload key is reset through Play support; the app signing key, held by Google,
is unaffected.

The upload key's fingerprints, for step 6:

```
keytool -list -v -keystore frict-upload.jks -alias upload | grep -E 'SHA1|SHA256'
```

### 2. `release` environment and its secrets

```
R=frict-auth/frict-android
gh api -X PUT repos/$R/environments/release \
  -F 'deployment_branch_policy[protected_branches]=false' \
  -F 'deployment_branch_policy[custom_branch_policies]=true'
gh api -X POST repos/$R/environments/release/deployment-branch-policies \
  -f name=main -f type=branch
gh api repos/$R/environments/release/deployment-branch-policies --jq '.branch_policies[].name'

base64 -w0 frict-upload.jks | gh secret set KEYSTORE_BASE64 --env release -R $R
gh secret set KEYSTORE_PASSWORD --env release -R $R
gh secret set KEY_PASSWORD --env release -R $R
gh secret set KEY_ALIAS --env release -R $R --body upload
gh secret list --env release -R $R
```

`gh secret set` without `--body` prompts, so passwords stay out of shell
history. The last policy listing must print only `main`. A second POST for
`main` answers 303 with the existing policy.

### 3. Release Firebase config

The prod Firebase project's Android app `app.frict.android` gives
`app/src/release/google-services.json`; commit it (`git add -f`) in a
`feat:`/`fix:` PR, whose merge makes the first release. Its `play` job fails:
the app doesn't exist in Play yet.

### 4. Play Console: app, first upload, Play App Signing

1. Create app "Frict": App, Free.
2. Download the first release's AAB:
   `gh run download <run-id> -R frict-auth/frict-android -n release-aab`
3. Testing → Internal testing → Create new release. Accept Play App Signing
   with a Google-generated app signing key, upload
   `bundle/release/app-release.aab` and roll it out. Add yourself as a tester.
4. Don't re-run that `play` job: Play refuses a versionCode it already has.
   The next release uploads by itself.

Setup → App integrity then shows the app signing key and upload key
certificates; the upload key's must match step 1's.

### 5. Release service account

In the prod project. The provider admits only frict-android's
`repository_id`; the binding admits only jobs in the `release` environment,
i.e. on `main`. The binding matches the `environment` claim, not `sub`, whose
format differs for repos created after 2026-07-15. The service-account
commands call the IAM API, hence `iam.googleapis.com`.

```
P=<prod-project-id>
SA=frict-play-release@$P.iam.gserviceaccount.com
gcloud projects describe $P --format='value(projectNumber)'
N=<prod-project-number>
POOL=projects/$N/locations/global/workloadIdentityPools/github-actions

gcloud services enable iam.googleapis.com iamcredentials.googleapis.com \
  sts.googleapis.com androidpublisher.googleapis.com --project=$P
gcloud iam workload-identity-pools create github-actions --project=$P \
  --location=global --display-name="GitHub Actions"
gcloud iam workload-identity-pools providers create-oidc github --project=$P \
  --location=global --workload-identity-pool=github-actions \
  --issuer-uri=https://token.actions.githubusercontent.com \
  --attribute-mapping=google.subject=assertion.sub,attribute.repository_id=assertion.repository_id,attribute.environment=assertion.environment \
  --attribute-condition="assertion.repository_id=='1440746369'"
gcloud iam service-accounts create frict-play-release --project=$P \
  --display-name="Frict Play release (GitHub Actions)"
gcloud iam service-accounts add-iam-policy-binding $SA --project=$P \
  --role=roles/iam.workloadIdentityUser \
  --member="principalSet://iam.googleapis.com/$POOL/attribute.environment/release"

R=frict-auth/frict-android
gh variable set PLAY_WIF_PROVIDER --env release -R $R --body "$POOL/providers/github"
gh variable set PLAY_SERVICE_ACCOUNT --env release -R $R --body "$SA"
gh variable list --env release -R $R
```

The `play` job fails before authenticating while either variable is empty.

Play Console → Users and permissions → Invite new users: `$SA`, app
permissions for Frict only, "Release apps to testing tracks".

### 6. At M3: release API key

In the prod project, restrict the release Android API key to
`app.frict.android` signed by the Play app signing key (Play Console → App
integrity) or the upload key (step 1), and to the APIs FCM needs:

```
gcloud services api-keys update <key-id> --project=<prod-project-id> \
  --allowed-application=sha1_fingerprint=<app-signing-SHA1>,package_name=app.frict.android \
  --allowed-application=sha1_fingerprint=<upload-SHA1>,package_name=app.frict.android \
  --api-target=service=fcmregistrations.googleapis.com \
  --api-target=service=firebaseinstallations.googleapis.com
```
