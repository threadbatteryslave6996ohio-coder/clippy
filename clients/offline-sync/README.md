# Offline Clipboard Sync Client

This one-shot Java client synchronizes clipboard records from the Linux client's
`clippy-offline-clipboard.json` file to the Clippy server.

It queries the server for the file's inclusive timestamp range, compares records
by `clientId`, `content`, and `timestamp`, and posts only records that are not
already stored. Auth audit records (`"type": "auth"`) are ignored because they
are not clipboard data. The source JSON file is never modified.

Configure the same values used by the clipboard client in the repository `.env`:

```dotenv
REMOTE_SERVER_URL=http://localhost:8080
AUTH_SERVER_URL=http://localhost:8081
CLIENT_ID=ubuntu-gnome
CLIENT_SECRET=change-me-please
```

`CLIENT_TOKEN` can be used instead of `CLIENT_SECRET`. If `CLIENT_ID` is omitted,
the sync client uses the single client id found in the file.

Run it from the repository root:

```bash
./scripts/start-file-locker.sh
```

Keep the file-locker running, then sync in another terminal:

```bash
./scripts/sync-offline-client.sh
```

The default input is `clippy-offline-clipboard.json`. Pass another path as the
first argument when needed:

```bash
./scripts/sync-offline-client.sh /path/to/clippy-offline-clipboard.json
```

The sync client obtains the JSON snapshot through the same Unix-domain socket
used for Linux-client appends. It never reads the file directly, so an append
cannot overlap a snapshot read.
