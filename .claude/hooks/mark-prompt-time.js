// UserPromptSubmit hook: stamps this turn's start time + prompt text into a
// sentinel file so the Stop hook (task-log-reminder.js) can tell whether
// task.md was updated for the task just requested, and can skip nudging on
// obvious filler replies ("yes", "continue"). Deterministic capture only --
// no summarization happens here, that's the agent's job at Stop time.
const fs = require("fs");
const path = require("path");

let data = "";
process.stdin.on("data", (c) => (data += c));
process.stdin.on("end", () => {
  let prompt = "";
  try {
    prompt = JSON.parse(data).prompt || "";
  } catch (e) {
    return;
  }
  const sentinel = path.join(
    process.env.CLAUDE_PROJECT_DIR || ".",
    ".claude",
    ".task-log-sentinel"
  );
  try {
    fs.writeFileSync(
      sentinel,
      JSON.stringify({ at: new Date().toISOString(), prompt: prompt.slice(0, 300) })
    );
  } catch (e) {
    // best-effort -- a failed stamp just means the Stop hook won't nudge this turn
  }
});
