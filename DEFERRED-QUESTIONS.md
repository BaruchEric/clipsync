# Deferred questions / decisions for Eric

Running autonomously through M3→M5 per "continue non-stop, defer questions till I'm back."
Decisions I made without you are logged here with my reasoning, so you can veto any.

## Open questions (need your input eventually)

- **M2 real-hardware confirmation.** Emulator proves background capture works via Shizuku on Android 16. Confirm on your actual phone when convenient (install clipsync + Shizuku, start Shizuku, background clipsync, copy in another app → should appear in history).

## Autonomous decisions (FYI — veto if wrong)

- **M3 crypto lib:** ionspin libsodium verified working on JVM — kept it, no Lazysodium fallback needed.
- **M3 SAS format:** 6-digit numeric code derived from the per-pair key (blake2b). Simple to compare on both screens.
- **M3 per-pair key:** X25519 DH hashed with both public keys (sorted) via blake2b → symmetric key both devices derive identically. Stored in the peer table (app-private DB); the long-term X25519 *secret* goes in Keychain/Keystore, never the DB.
- **M3 QR UI (camera scan on Android, QR render on desktop):** NOT yet built — the pairing *protocol* is proven end-to-end in simulation (PairingProtocolTest), but the on-screen QR + camera capture is device/UI work still pending. Will need your on-device verification once built.
- **M3 not tagged yet** — protocol/crypto/identity core is done and tested; QR UI + TLS cert generation (part of M4 transport) remain before tagging m3.
- **libsodium tests run desktop-only:** ionspin can't load native libsodium in the Android host-JVM unit test, so crypto tests live in desktopTest. The crypto code itself is commonMain and runs on-device fine.
