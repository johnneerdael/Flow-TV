# Public plugin API contract

This workspace keeps the TypeScript SDK and JSON schema aligned with Milkbeat's native plugin API. The app's generic plugin runtime, installation, updates, sign-in and download-code support remain in the public app repository.

```sh
npm ci
npm run check
```

Compiled third-party packages and their publication metadata are retained as verification data for the app CI; they are not included in the APK or attached to app releases. Forgejo synchronizes that data and the encrypted download catalog.

Provider implementations, package building/signing tools and example plugin generation live in the separate private Milkbeat-Plugins repository on Forgejo. Plugin packages and their encrypted code-to-URL catalogs are published there independently. Milkbeat releases contain the app APKs and checksums; the app's README lists optional third-party downloader codes.
