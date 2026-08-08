// PostToolUse hook: reminds to verify apps/web/ TypeScript edits with npx tsc --noEmit
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
  if (normalized.includes("apps/web/src/")) {
    console.log(
      JSON.stringify({
        systemMessage:
          "Frontend apps/web/ src files changed. Verify type safety before completing: cd apps/web && npx tsc --noEmit",
      })
    );
  }
});
