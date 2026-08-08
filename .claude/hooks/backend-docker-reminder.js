// PostToolUse hook (Write|Edit): reminds that services/backend/ (Python) edits
// don't hot-reload inside the skolab_python_ai docker container — only
// `uvicorn --reload` picks them up automatically. See AGENTS.md: "the single most
// common source of 'I fixed it but the bug is still there' confusion in this repo."
let data = "";
process.stdin.on("data", (c) => (data += c));
process.stdin.on("end", () => {
  let filePath = "";
  try {
    const input = JSON.parse(data);
    filePath = (input.tool_input && input.tool_input.file_path) || (input.tool_response && input.tool_response.filePath) || "";
  } catch (e) {
    return;
  }
  const normalized = filePath.replace(/\\/g, "/");
  if (normalized.includes("services/backend/")) {
    console.log(
      JSON.stringify({
        systemMessage:
          "services/backend/ changed. If you're testing against the skolab_python_ai docker container (not uvicorn --reload), this won't take effect until you rebuild: cd services/backend && docker compose build web && docker compose up -d --no-deps web",
      })
    );
  }
});
