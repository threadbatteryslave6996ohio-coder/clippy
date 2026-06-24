# Clippy Android

Kotlin Android client for posting text clipboard entries to the Clippy server.

## Behavior

- Paste reads the current Android clipboard while the app is open.
- Send posts the text to `POST /clipboard`.
- Auto sync checks the clipboard every two seconds while the screen is open.
- Android share sheet support accepts `text/plain` shares into Clippy.

Android does not allow a normal third-party app to silently monitor clipboard changes in the background like a desktop client. Keep the app open for polling, or share text into Clippy from another app.

## Build

Open `clients/android` in Android Studio and run the `app` configuration.

Start the server from the repository root before sending clipboard entries:

```bash
docker compose up -d postgres
mvn -pl server spring-boot:run
```

The app allows cleartext HTTP so it can talk to a local development server:

```text
http://192.168.1.10:8080
```

Use your computer's LAN IP address from a physical Android device. `localhost` on the phone points at the phone itself, not the server machine.

For an emulator, use:

```text
http://10.0.2.2:8080
```

## Server contract

The Android app sends the same payload as the mac client:

```json
{
  "clientId": "android-pixel-8",
  "content": "clipboard text",
  "timestamp": "2026-06-23T12:00:00Z"
}
```
