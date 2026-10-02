# Signing

Every APK is signed with the same key so new versions install over old ones
(keeping your settings). This key is committed so the build works with no setup.

Anyone who has this key could sign an app that installs as an "update" to Halo,
so only install APKs from this repo's Releases page. To use a private key instead,
add repository secrets `HALO_KEYSTORE_B64` (base64 of a .jks) and
`HALO_KEYSTORE_PASSWORD`; the workflow uses them when present. Switching keys
means uninstalling the old build once.
