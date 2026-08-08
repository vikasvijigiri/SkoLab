// Stop hook: nudges to log the task just handled into task.md (see
// .claude/skills/log-task/SKILL.md for the entry format) if task.md wasn't
// touched since the prompt that started this turn. Purely a deterministic
// mtime check -- it can't judge whether an entry's content is actually good,
// same division of labor as the HANDOFF.md nudge (see CLAUDE.md).
const fs = require("fs");
const path = require("path");

const root = process.env.CLAUDE_PROJECT_DIR || ".";
const sentinelPath = path.join(root, ".claude", ".task-log-sentinel");
const taskLogPath = path.join(root, "task.md");

let sentinel;
try {
  sentinel = JSON.parse(fs.readFileSync(sentinelPath, "utf8"));
} catch (e) {
  process.exit(0);
}

const FILLER =
  /^(y|yes|yep|yeah|ok|okay|k|sure|go ahead|continue|do it|please proceed|proceed|thanks|thank you|no|nope)[.!]?$/i;
if (FILLER.test((sentinel.prompt || "").trim())) {
  process.exit(0);
}

let taskLogMtime = 0;
try {
  taskLogMtime = fs.statSync(taskLogPath).mtimeMs;
} catch (e) {
  // task.md missing entirely -- definitely nudge (taskLogMtime stays 0)
}

const sentinelMtime = new Date(sentinel.at).getTime();

if (taskLogMtime < sentinelMtime) {
  console.log(
    JSON.stringify({
      systemMessage:
        "task.md hasn't been updated for the task just handled. Append an entry (date-time header, 6-line brief, status) per .claude/skills/log-task/SKILL.md -- don't edit past entries, append-only.",
    })
  );
}
