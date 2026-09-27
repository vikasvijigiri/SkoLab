//go:build !windows

package texsandbox

import (
	"os/exec"
	"syscall"
)

// isolateProcessGroup puts cmd in its own process group (setpgid) so
// killProcessGroup can take down every descendant, not just the direct
// child, when the compile times out.
func isolateProcessGroup(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
}

func killProcessGroup(cmd *exec.Cmd) error {
	if cmd.Process == nil {
		return nil
	}
	err := syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL)
	if err == syscall.ESRCH {
		return nil // already exited
	}
	return err
}
