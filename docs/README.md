# Documentation map

Use the smallest current document that answers the question. Dated plans,
research, recon, postmortems, and decisions preserve context; they are not
automatically current implementation instructions.

| Directory or file | Use it for |
| --- | --- |
| `architecture/` | system boundaries and dependency direction |
| `ops/` | deployment, monitoring, security, and tooling guidance |
| `runbooks/` | incidents, recovery, capacity, and operational procedures |
| `checklists/` | release and quality checklists |
| `plans/`, `specs/`, `research/`, `recon/` | dated planning and evidence |
| `postmortems/` | incident learning records |
| `backend-auth-posture.md` | current authentication and authorization posture |

The source-of-truth production configuration is `render.yaml`; API contract
compatibility is checked by `scripts/verify_contracts.py`.
