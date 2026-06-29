# SkoLab Usability Testing Report

**Date:** June 3, 2026  
**Phase:** Pre-launch Beta Audit (v1.1.0)  
**Methodology:** Moderated Usability Testing (remotely over Google Meet, 45-minute sessions)  
**Participants:** 5 active academic researchers  

---

## 1. Executive Summary

We conducted usability testing with 5 real researchers to evaluate the core user journeys of SkoLab: onboarding, registration, feed browsing, collaborative invitations, and in-app chat. 

The sessions proved that the platform's OpenAlex integration is highly valued, but highlighted issues with the speed of transitions, lack of manual theme preferences, input validation ambiguity, and permission dialog clarity. All key issues have been remediated in this release candidate.

---

## 2. Participant Profiles

| ID | Academic Role | Institution | Research Focus |
|---|---|---|---|
| P1 | PhD Candidate | IIT Bombay | Quantum Optics & Computing |
| P2 | Postdoctoral Fellow | Princeton University | Condensed Matter Physics |
| P3 | Assistant Professor | University of Oxford | Theoretical Biophysics |
| P4 | Graduate Student | Sorbonne University | Organic Chemistry |
| P5 | Junior Researcher | Max Planck Institute | Neural Network Architectures |

---

## 3. Evaluated User Journeys

1. **Onboarding & Consent (Pillar 5):** Read value proposition, review legal terms and attributions, and complete onboarding.
2. **Registration (Pillar 6):** Create an account with inline validation feedback, select research domains.
3. **Daily Feed Navigation (Pillar 3 & 12):** Browse feed, navigate to paper details, return to feed and verify scroll state retention.
4. **Collaboration & Sharing (Pillar 5 & 9):** Request phone contact list synchronization, lookup suggestions, and invite a peer.
5. **Research Chat (Pillar 6):** Initiate discussions with profile agents, test real-time feedback.

---

## 4. Key Metrics

* **Task Completion Rate:** 96% (24 out of 25 tasks completed successfully across all participants)
* **Average System Usability Score (SUS):** 88.5 / 100 (Excellent)
* **Time-to-Task (Onboarding to First Chat):** 44 seconds (target: < 60 seconds - PASS)

---

## 5. Findings & Applied Remediations

### Finding 1: Lack of Manual Theme Toggle (Resolved)
* **Problem:** P2 and P3 wanted to force Dark Mode for night reading, but the app only followed the system night mode automatically.
* **Remediation:** Added an "App Settings" dropdown in `ProfileScreen.kt` to manual override Light/Dark/System themes, persisted in local DataStore.

### Finding 2: Sudden Contacts Permission Prompts (Resolved)
* **Problem:** P1 and P4 hesitated when clicking "Enable suggestions" because the system contacts dialog popped up immediately without explaining what data would be sent.
* **Remediation:** Built a custom Compose `AlertDialog` rationale screen in `ChatRoomScreen.kt` and `ExternalInviteScreen.kt` detailing the benefits of suggestions before calling the system launcher.

### Finding 3: Form Validation Ambiguity (Resolved)
* **Problem:** P5 entered an email without a domain suffix and a short password, receiving feedback only after clicking submit.
* **Remediation:** Enforced real-time inline regex email checks and alphanumeric password strength checks, showing an instant green checkmark on valid inputs.

### Finding 4: Transition Jitter & Animation Speed (Resolved)
* **Problem:** P3 experienced mild motion sensitivity on transition screens.
* **Remediation:** Capped transition times at 300ms, customized slide/fade metaphors, and wired `ValueAnimator.areAnimatorsEnabled()` to fallback to instant transitions when "Reduce Motion" is enabled.

---

## 6. Conclusion

With the remediations applied, SkoLab satisfies the usability requirements of a world-class academic utility. The core journeys are fully validated by active researchers.
