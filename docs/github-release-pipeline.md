# GitHub Release Pipeline

This repository ships Android releases from GitHub Actions with two workflows:

- `.github/workflows/ci.yml`: runs tests, lint, and a debug build on `main` and pull requests.
- `.github/workflows/release.yml`: manually builds a signed `.aab` and uploads it as a GitHub artifact for manual submission to Google Play Console.

## Required GitHub setup

Create two GitHub Environments in the repository settings:

- `internal`
- `production`

Recommended:

- add required reviewers on `production`
- keep the signing secrets in `production`

## Required secrets

Add these secrets in GitHub under `Settings -> Secrets and variables -> Actions`:

- `ANDROID_KEYSTORE_BASE64`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

## Export values from your local machine

### 1. Keystore as base64

PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\release.keystore"))
```

Git Bash:

```bash
base64 "C:/path/to/release.keystore" | tr -d '\n'
```

Copy the output into the `ANDROID_KEYSTORE_BASE64` GitHub secret.

### 2. Signing values from local.properties

Copy these values from your local `local.properties`:

```properties
releaseStorePassword=...
releaseKeyAlias=...
releaseKeyPassword=...
```

Store them in the matching GitHub secrets:

- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

## Running a release

1. Open `Actions -> Release`.
2. Click `Run workflow`.
3. Enter a new `version_name`, for example `1.0.1`.
4. Enter a strictly increasing `version_code`, for example `2`.
5. Run the workflow.

The workflow will:

- rebuild the signed release bundle
- upload the `.aab` as a GitHub Actions artifact

## Submitting to Google Play manually

1. Open the finished workflow run.
2. Download the generated artifact.
3. Extract the `.aab` file.
4. Open Google Play Console.
5. Create or update the release on the desired track.
6. Upload the `.aab` manually.

## Notes

- `version_code` must always increase on Google Play.
- `version_name` is the visible app version shown to users.
- local builds still work through `local.properties`.
- CI and release builds currently use JDK 25 because the project is configured for Java 25 in `app/build.gradle.kts`.
- automatic Play publishing can be added later by wiring a Google Play service account JSON secret.
