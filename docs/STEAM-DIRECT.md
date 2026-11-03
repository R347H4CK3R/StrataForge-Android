# Direct Steam integration specification

The Android application connects to Steam directly, with **no PC bridge**.

## Device target (verified via ADB)
onn. 10.1 / Android 15 / ARM64 / Mali-G52 MC2 / ~2.8 GiB RAM / ~6.3 GiB free storage at inspection.

## Functional requirements
- Steam Guard-capable account sign-in and account session logout.
- Show an authenticated Steam library and app ownership from Steam network protocols (not Steam OpenID alone).
- Download only account-authorized depots from Steam's content delivery system.
- Full download size calculation, free-space preflight check, progress, pause/resume, and integrity verification.
- User-selectable app-scoped storage; storage permission model for Android 15.
- Avoid collecting Steam credentials in StrataForge's own WebView/forms. Prefer official authentication challenge and delegated authentication through an audited Steam protocol client; protect session keys with Android Keystore.
- Never upload Steam credentials, cookies or tokens to GitHub or third-party bridge services.

## Integration candidate
Evaluate SteamKit and DepotDownloader (SteamRE). The .NET client cannot be assumed to run in a standard Java-only Android APK. A validated Android ARM64 managed runtime or port is required. Dependencies and library licensing need review before packaging. Steam OpenID is identity proof, NOT an entitlement or depot download authorization token.

## Constraints
Steam download support does not imply that Windows games can execute. The Mali-G52 is not within DroidDeck's currently advertised compatible GPU set. Runtime compatibility must be separately implemented and verified.

## Verification gates
1. Steam authentication on physical tablet, including Steam Guard, with credentials retained only inside the user-controlled device.
2. Read the authenticated user's library.
3. Download a small licensed title with integrity checking and resume.
4. Restart app and verify durable download state.
5. Verify insufficient-storage error handling and safe sign-out.
6. Only then label download support complete.

**Current state:** Specification only. Direct Steam sign-in, authenticated library and depot downloads are not implemented in v0.1.0.
