# Clippy Linux Client

Java clipboard client for posting Ubuntu GNOME text clipboard changes to the Clippy server.

The client is an explicit foreground process. It reads the local text clipboard, checks for a change from the last successfully sent value, and posts changed content to the server.

## Requirements

- Ubuntu GNOME on ARM or x86_64
- JDK 17+ with `javac` available on `PATH`
- Maven 3.9+
- A running Clippy server
- `wl-clipboard` for GNOME Wayland, or `xclip`/`xsel` for X11

Install the recommended Ubuntu packages:

```bash
sudo apt install openjdk-17-jdk maven wl-clipboard xclip
```

## Run the Client

From the repository root:

```bash
export REMOTE_SERVER_URL=http://localhost:8080
export CLIENT_ID=ubuntu-gnome
export CLIPBOARD_POLL_INTERVAL_MS=1000
mvn -pl clients/linux package
java -jar clients/linux/target/clippy-linux-client-0.1.0-SNAPSHOT.jar
```

`REMOTE_SERVER_URL` is required. It may be either the server base URL, such as `http://localhost:8080`, or the full endpoint, such as `http://localhost:8080/clipboard`.

`CLIENT_ID` is optional and defaults to the machine hostname. `CLIPBOARD_POLL_INTERVAL_MS` is optional and defaults to `1000`.

## Clipboard Backend

Backend selection is automatic:

- GNOME Wayland uses `wl-paste` from `wl-clipboard`.
- X11 uses `xclip` when installed, then `xsel`.
- Java AWT is used as a fallback when a graphical clipboard is available.

Override automatic selection with `CLIPBOARD_BACKEND`:

```bash
export CLIPBOARD_BACKEND=wl-paste
```

Supported values are `wl-paste`, `wayland`, `xclip`, `xsel`, `awt`, and `java`.

## Server Contract

The client sends:

```json
{
  "clientId": "ubuntu-gnome",
  "content": "clipboard text",
  "timestamp": "2026-06-23T12:00:00Z"
}
```
