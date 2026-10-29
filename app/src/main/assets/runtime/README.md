# Runtime TLS assets

These files are extracted by `NodeRuntime.prepare()` into the app-private prefix
(`files/usr/etc/tls`) before any Node.js/npm process is started.

- `openssl.cnf` — OpenSSL 3 configuration template. `@PREFIX@` and `@HOME@` are
  replaced with the actual app paths at setup time. It intentionally contains no
  `/data/data/com.termux` reference.
- `cert.pem` — Mozilla CA root bundle (MPL-2.0), copied from
  <https://github.com/bagder/ca-bundle> (`ca-bundle.crt`). Used as
  `SSL_CERT_FILE` / `NODE_EXTRA_CA_CERTS` so npm and Node TLS clients can verify
  registry certificates without depending on the Termux prefix.

The bundle is a snapshot; update it periodically from the upstream repository.
User-provided runtime archives may still supply their own `etc/tls/openssl.cnf`;
`NodeRuntime` rewrites any hardcoded Termux prefix found in that file to the
NodeSphere prefix instead of overwriting the user's configuration.
