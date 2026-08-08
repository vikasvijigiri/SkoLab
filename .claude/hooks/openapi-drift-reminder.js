// PostToolUse / Stop hook: reminds that API route edits in services/backend/ or services/backend-go/
// require updating api-contracts/openapi.yaml per AGENTS.md.
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
  if (normalized.includes("services/backend/app/api/") || normalized.includes("services/backend-go/")) {
    console.log(
      JSON.stringify({
        systemMessage:
          "API route files changed. Ensure api-contracts/openapi.yaml is updated to match, or run the openapi-contract-sync skill to verify contract alignment.",
      })
    );
  }
});
