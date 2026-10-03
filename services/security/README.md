# Security release checks

The reusable `security-scan.yml` is a release dependency in backend CI. It also
runs weekly and manually. Failures prevent the normal CI release job deploying.

On 2026-10-03, Semgrep 1.176.0 scanned the Python/Go application code with
`p/python`, `p/security-audit`, and `p/secrets`: zero findings. Trivy 0.75.0
scanned the repository's Go module, Python requirements, and four Dockerfiles:
zero HIGH/CRITICAL vulnerability or configuration findings. These results do
not cover a running deployment or installed OS packages in built images.

CI now scans both the production API image and compile-worker image. No Docker
runtime was available locally to execute those image scans. Semgrep scans Python
and Go source; Trivy owns Dockerfile/configuration checks. Semgrep is pinned;
the security actions are pinned to commits resolved from their official tags.

The gate blocks all Semgrep findings and fixable HIGH/CRITICAL Trivy findings.
Unfixed vulnerabilities remain in the report and require review; they are not
automatically treated as safe. Scanner/tool errors fail their scan steps. SARIF
upload errors can remain nonblocking because private/fork permissions may prevent
uploading, while the actual scans still enforce the gate.

Manual release workflows also verify successful test and security jobs for the
exact commit before deploying. Smoke-only workflows can still diagnose production
without deploying. `verify_ci.py` checks the core jobs independently of the old
release result so a deployment failure can be retried after passing checks.

There are currently no finding suppressions. If one becomes necessary, record
the specific rule/CVE, affected package or path, evidence, owner, remediation,
and expiry date in this directory. Avoid whole-file or whole-repository ignores.
For vulnerabilities, use Trivy's supported VEX or expiring ignore configuration
after reviewing actual reachability. Rotate any genuine leaked secret before
considering a historical finding resolved.
