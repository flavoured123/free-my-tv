The key this fork's APKs are signed with (`.github/workflows/apk.yml`). Updates install over the app only when signed
with the same key, so keep this file. It's here, not in a secret, because the GitHub app can't easily paste a binary
secret; to move it into one, add a `KEYSTORE` secret (base64 of this file) and delete it here.
