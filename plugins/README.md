# Public plugin API contract

This workspace keeps the TypeScript SDK and JSON schema aligned with Milkbeat's native plugin API. The app's generic plugin runtime, installation, updates, sign-in and download-code support remain in the public app repository.

```sh
npm ci
npm run check
```

Compiled third-party packages and their publication metadata are retained as verification data for the app CI; they are not included in the APK or attached to app releases. Publication updates supply that data and the encrypted download catalog.

Third-party plugin packages are distributed separately from Milkbeat. Milkbeat releases contain the app APKs and checksums; the app's README lists optional third-party downloader codes.
