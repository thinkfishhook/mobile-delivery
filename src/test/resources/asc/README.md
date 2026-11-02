# asc command recordings

Captured on 2026-11-02 using the bundled release's `asc` 5.7.0 macOS arm64
binary (`asc_5.7.0_macOS_arm64`, original SHA-256
`dc6a75cb1996e29275de3c8671a6d7513b0f995abef6b79c4a8619e828f0a086`).

These are real CLI stdout, stderr and exit codes against a local HTTP fixture
server, not live Apple account failures. To direct the binary to that server
without changing trust settings, its API base URL was replaced with the equal-length
`http://api.appstoreconnect.apple.com/` and the binary was ad-hoc signed.
`HTTP_PROXY` directed requests to localhost. No other CLI behavior was modified.

Command: `asc builds list --app 123 --output json`, with strict auth, a temporary
synthetic P-256 PKCS#8 key, neutral key/issuer IDs and an isolated HOME.
The server returned `{"data":[]}` for 200, and JSON:API errors with `status`,
`code` and `title` for each failure:

| HTTP | Code | Title |
| --- | --- | --- |
| 401 | NOT_AUTHORIZED | Authentication credentials are missing or invalid. |
| 403 | FORBIDDEN_ERROR | The given operation is not allowed. |
| 500 | UNEXPECTED_ERROR | An unexpected error occurred. |

The malformed recording used `ASC_PRIVATE_KEY=not a key` and made no HTTP
request. Real 401/403 output omits the status but uses distinct titles/hints;
both exit 3. The 500 exits 60. Tests also cover explicit HTTP status output and
process timeouts separately. No real credentials or account identifiers were used.
