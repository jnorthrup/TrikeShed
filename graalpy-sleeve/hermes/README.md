# Hermes → GraalPy sleeve

This directory shadows modules from the live `~/.hermes/hermes-agent` checkout without copying or
forking that checkout. A path has normal Python module identity:

- `pydantic/__init__.py` shadows module `pydantic`
- `agent/transport.py` shadows module `agent.transport`

`./gradlew portHermesPython` rebuilds a trimmed-string ontology spine every run. Its semantic zoom is:

1. `L0`: ready / blocked / deferred
2. `L1`: native blocker or upstream / sleeve origin
3. `L2`: top-level Python package
4. `L3`: full module path

A sleeve module may replace a banned module only when it exists here and its own required-import graph
is clean. GraalPy still runs with host access, IO, process creation, environment access, polyglot access,
and native access disabled. Its only filesystem is a supervisor-owned `UserspaceBtrfs` subvolume;
language-internal resources are admitted separately, while host files and sockets remain denied.
Missing APIs fail closed; do not add inert compatibility stubs.

Curated waists currently include `yaml` (TrikeShed YAML/Confix parser through a host delegate) and
`dotenv` (pure GraalPy over the Btrfs VFS). They are sufficient for `hermes_cli.main` to import; network
provider plugins that reach banned `socket` remain intentionally unavailable until userspace.nio
delegates replace them.

Daily outputs:

- `build/reports/hermes-python-port.json` — full inventory, ontology spine, four zoom levels, daily delta
- `build/reports/hermes-graalpy-sleeve-queue.json` — top blocker roots ranked by impacted modules
