# Graal Pi

Graal Pi runs the pinned Pi agent core in a GraalJS child process and presents
the Pi page in a browser. Model calls go through the host's ModelMux and KeyMux.
The package does not include a model or credentials. Its restricted JS guest is
not a Node.js shell, and process isolation alone is not an operating-system
sandbox or an airgap.

## Run

Install a Java 25-compatible runtime with a working `bin/java`, set `JAVA_HOME`
or put `java` on `PATH`, and unpack this archive. Provide these settings in the
host environment without putting secrets into a command line or the workspace:

- `OPENAI_API_KEY`: the API key for the configured OpenAI-compatible provider
  (required unless the local development relay mode below is selected).
- `PI_MODEL`: the model identifier offered by that provider.
- `PI_ACCESS_TOKEN`: a local Pi access token (17–2041 characters), or instead
  `PI_ACCESS_TOKEN_SHA256`, its lowercase 64-character SHA-256 hash. Enter the
  original token in the Pi page; the hash cannot be used as the page token.
- `PI_BASE_URL` (optional): the provider's API base URL; defaults to
  `https://api.openai.com/v1`.

Start with an existing workspace directory and explicitly allow the files Pi
may edit. Paths after `--allow` are relative to the workspace, must already
exist, and must end in `.ts` or `.js`. With no `--allow`, editing is disabled.

```sh
bin/graal-pi --workspace /path/to/workspace --allow model-entry.ts --port 0
```

Open the loopback URL printed at startup. The file supplied in this example
must already be present as `/path/to/workspace/model-entry.ts`; the package
includes the Pi adapter's source under `source/pi-core` as a starting point.
Changes to a source copy do not automatically rebuild or replace the bundle
embedded in the running JVM. The page uses the configured token and requires a
working model/provider to run a turn. Network access to a remote provider is
not airgapped; a locally hosted provider must be configured separately.

For a local development relay, set `PI_AUTH=none` and an explicit `PI_BASE_URL`
whose host is the numeric loopback address `127.0.0.1` or `[::1]`. This opts
out of provider authentication only for that local endpoint; it does not start a
model or a relay. A manually operated relay requires an operator to answer its
requests and is not an unattended inference service. The default is
`PI_AUTH=required` for a configured OpenAI-compatible API.

## Rebuild and verify

From a matching source checkout, using JDK 25 and the project's Gradle wrapper:

```sh
./gradlew graalPiZip graalPiTar
```

The archives are written under `build/distributions`. Unpack an archive into a
fresh directory and test the launcher, page, model request and an explicitly
allowed edit before publishing it. The curated runtime classpath is separate
from the full daemon classpath and must be tested as an installed package. The
JVM and generated browser bundles are built from the checkout; the pinned Pi
bundle, its SHA-256 and upstream/bundling details are in `source/pi-core`.
Rebuilding that pinned bundle also requires the upstream source and build inputs
identified in its manifest and notice; they are not all included here.

The archive includes the project AGPL license and notice and the Pi and TypeBox
licenses. The corresponding full project source is available from the matching
source revision of <https://github.com/jnorthrup/TrikeShed>; this archive's
`source` directory provides the Pi-facing files, not the entire project source.
