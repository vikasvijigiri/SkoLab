# MCP setup

The root `.mcp.json` is the local MCP client configuration for SkoLab. It is
kept out of git because MCP client configuration is machine-specific; use
[`.mcp.json.example`](../../.mcp.json.example) as the shareable template.

## Setup

1. Install the Firebase CLI globally: `npm install --global firebase-tools@15.30.1`.
2. Copy `.mcp.json.example` to `.mcp.json` if the local file is missing.
3. Set `DATABASE_URL` for the Postgres server.
4. Set `TAVILY_API_KEY` if Tavily search is needed.
5. Authenticate the HTTP servers in the MCP client: Sentry, GitHub, and Figma.
6. Authenticate Firebase CLI (`firebase login`) and select the intended
   project before using the Firebase MCP.
7. For mobile MCP, have an Android emulator/device available and ADB working.
8. Restart the MCP-capable client after changing `.mcp.json` or environment
   variables.

All npm-backed servers are version-pinned in the configuration. No API keys,
database URLs, or OAuth tokens belong in this repository.
