# Running from source

This is for people building or contributing to Resolvr itself. If you just want to *use* it against your own repos, see the [README](../README.md) — deploy the prebuilt image, no Java toolchain required.

## Prerequisites

- JDK 21
- A GitHub token with `repo`, `read:discussion`, `write:discussion` scopes — or just run `gh auth login` first; `GitHubTokenResolver` falls back to `gh auth token` automatically if `GITHUB_TOKEN`/`github.token` isn't set.

## Fast path: dev mode

```bash
./mvnw quarkus:dev
```

Starts in a few seconds with hot reload. Auth is off by default in the dev profile (`%dev.resolvr.api-key=`, `%dev.github.webhook.secret=` are both blank in `application.properties`), so there's nothing else to configure for local testing. Skip the webhook entirely for a quick check — just tell your IDE agent to call `fetch_pr_comments` directly for a PR you already know about.

## Full local run (matches production config)

```bash
export GITHUB_TOKEN=ghp_your_token_here          # repo, read:discussion, write:discussion scopes
export GITHUB_WEBHOOK_SECRET=your_webhook_secret  # HMAC secret for your GitHub webhook
export RESOLVR_API_KEY=$(openssl rand -hex 32)    # required for anything beyond localhost
./scripts/run.sh
```

Builds via Maven, then runs the packaged jar. Point your GitHub repo/org webhook at `http://<your-host>:8080/webhook/github`, then connect your IDE's MCP client to `http://<your-host>:8080/mcp/sse`. `.vscode/mcp.json` and `.idea/mcp.xml` in this repo are already wired to `localhost:8080`.

## Docker, built locally

```bash
docker compose -f docker/docker-compose.yml up --build
```

Builds the multi-stage `docker/Dockerfile` from source rather than pulling the published GHCR image — useful when testing a local change to the image itself.

## Tests

```bash
./mvnw verify
```

See [TESTING.md](TESTING.md) for coverage and how failure scenarios are handled.

## Publishing

Every push to `main` builds and pushes `ghcr.io/deadserpent/resolvr` (`:latest` and `:<sha>`) via `.github/workflows/ci.yml` — see that workflow for the exact steps if you're changing the image build.

## Learn more

- [Architecture](ARCHITECTURE.md) — how the pieces fit together
- [MCP tools reference](TOOLS.md) — the full tool list an IDE agent can call
- [Security](SECURITY.md) — API key auth, confirmation mode, token permission gotchas
- [Known limitations](LIMITATIONS.md) — what this doesn't do (yet)
