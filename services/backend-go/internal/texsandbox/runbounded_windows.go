//go:build windows

package texsandbox

import "os/exec"

// Windows has no process-group signal equivalent to POSIX SIGKILL-to-group;
// this only kills the direct child. Acceptable for local dev/testing —
// production runs on Linux (the deployed container), where the POSIX path
// in runbounded_unix.go applies.
func isolateProcessGroup(cmd *exec.Cmd) {}

func killProcessGroup(cmd *exec.Cmd) error {
	if cmd.Process == nil {
		return nil
	}
	return cmd.Process.Kill()
}
