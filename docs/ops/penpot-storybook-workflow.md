# SkoLab Penpot + Storybook workflow

SkoLab uses Penpot as the free, editable design source and Storybook as the
executable component source. Playwright and axe provide the release evidence.

## Penpot MCP

The repo's local `.mcp.json` includes `@penpot/mcp@stable`. The file is ignored
because it is machine-local; `.mcp.json.example` is the shareable template.

1. Run `npx -y @penpot/mcp@stable` and keep that terminal open.
2. Open a Penpot file in `https://design.penpot.app`.
3. In Penpot, load the plugin from `http://localhost:4400/manifest.json`.
4. Start the plugin and choose **Connect to MCP server**.
5. Start with read-only prompts: list pages, list components, inspect tokens.
6. Make small writes only after the structure and token map are verified.

Never place a Penpot MCP key in this repository. For remote mode, keep the
server URL and `userToken` in the OS secret store or an ignored local config.

## Component workflow

1. Define primitive and semantic tokens before component styling.
2. Build component states in Storybook: default, hover, focus, pressed,
   disabled, loading, error, empty, and responsive where relevant.
3. Keep Figma/Penpot component properties aligned with TypeScript props.
4. Use Playwright screenshots and axe checks for the final rendered states.

Useful commands from `apps/web`:

```powershell
npm run storybook
npm run build-storybook
npm run check:contrast
npm run test:e2e
```
