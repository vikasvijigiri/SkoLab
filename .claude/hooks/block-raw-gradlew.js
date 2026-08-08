// PreToolUse hook (Bash|PowerShell): blocks raw gradlew invocations.
// The repo's historical checkout path contains the unicode symbol "π" (pi), which
// crashes Gradle in standard shells. scripts/build/build-and-install.ps1 works around
// this by copying apps/android-app into an ASCII-only temp dir before building.
let data = "";
process.stdin.on("data", (c) => (data += c));
process.stdin.on("end", () => {
  let cmd = "";
  try {
    cmd = (JSON.parse(data).tool_input || {}).command || "";
  } catch (e) {
    return;
  }
  const invokesGradlew = /(^|[;&|\s])\.{0,2}[\\/]?gradlew(\.bat)?(\s|$)/i.test(cmd);
  const viaWrapperScript = /build-and-install\.ps1/i.test(cmd);
  if (invokesGradlew && !viaWrapperScript) {
    console.log(
      JSON.stringify({
        hookSpecificOutput: {
          hookEventName: "PreToolUse",
          permissionDecision: "deny",
          permissionDecisionReason:
            "Raw gradlew is blocked in this repo — the checkout path contains the unicode symbol π, which crashes Gradle in standard shells (see AGENTS.md). Run scripts/build/build-and-install.ps1 instead; it copies apps/android-app into an ASCII-only temp dir before building.",
        },
      })
    );
  }
});
