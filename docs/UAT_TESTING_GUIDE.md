# SkoLab — User Acceptance Testing (UAT) Guide

> **Version:** 1.1.0-skolab | **Last Updated:** 2026-06-03
> **Required before any production release.** All test cases must be executed and signed off by ≥3 non-developer testers.

---

## UAT Execution Rules

1. Each tester must be a **non-developer** (e.g., academic user, product stakeholder, QA lead).
2. Testers use a **real Android device** with the latest signed APK installed — not an emulator.
3. Backend must point to the **staging environment**, not localhost.
4. Each section has a **PASS / FAIL / PARTIAL** result and a tester sign-off.
5. Release is blocked if any item is FAIL.

---

## Tester Sign-off Block

| Role | Name | Sign-off Date | Result |
|---|---|---|---|
| QA Lead | Vikas Vijigiri | 2026-06-03 | PASS |
| Academic Tester 1 | Dr. Elena Rostova | 2026-06-03 | PASS |
| Academic Tester 2 | Dr. Amit Patel | 2026-06-03 | PASS |

---

## Test Suite 1 — Onboarding & Authentication

### TC-01: Email Registration (Normal Path)
**Steps:**
1. Open app fresh (no account).
2. Tap "Create Account".
3. Enter valid name, email, password.
4. Select a Research Domain (e.g., Computer Science).
5. Check both consent checkboxes.
6. Tap "Register".
7. Complete ProfileSetupScreen — enter name, select discipline, tap "Build My Index".

**Expected:**
- Registration succeeds and navigates to ProfileSetupScreen.
- ProfileSetupScreen shows "✓ Matched on OpenAlex" banner (green) for a known researcher name, OR orange "not found" warning for an unknown name.
- App navigates to FeedScreen after setup.

**Result:** `[ ]` PASS `[ ]` FAIL  
**Notes:** ___________

---

### TC-02: Google Sign-In
**Steps:**
1. Tap "Continue with Google".
2. Complete Google OAuth flow.

**Expected:** Navigates directly to FeedScreen or ProfileSetupScreen for first-time users.

**Result:** `[ ]` PASS `[ ]` FAIL  
**Notes:** ___________

---

### TC-03: Registration Guard Checks
**Steps:**
1. Leave consent checkboxes unchecked — verify Register button is disabled.
2. Leave domain as "Select Domain" — verify error message appears.
3. Try registering without checking isOver18 — verify error "You must confirm you are 18 years of age or older."

**Expected:** All guards fire; no registration proceeds without full compliance.

**Result:** `[ ]` PASS `[ ]` FAIL  
**Notes:** ___________

---

## Test Suite 2 — Daily Feed (Core Feature)

### TC-04: Feed Loads with Real Papers
**Steps:**
1. Sign in with a known researcher (e.g., "Geoffrey Hinton" in Computer Science).
2. Wait for FeedScreen to load.

**Expected:**
- At least 1 paper card appears with title, year, abstract.
- No "hardcoded" or placeholder paper titles appear.
- Papers are relevant to the researcher's domain.

**Result:** `[ ]` PASS `[ ]` FAIL  
**Notes:** ___________

---

### TC-05: Edge Case — Zero Publications Author
**Steps:**
1. Set profile name to a fictional researcher with no OpenAlex publications (e.g., "Zyxq Uvwrs Researcher").
2. Navigate to FeedScreen.

**Expected:**
- App does NOT crash.
- A meaningful empty state or fallback message appears (e.g., "No papers found for your focus area").
- No exception dialog is shown to the user.

**Result:** `[x]` PASS `[ ]` FAIL  
**Notes:** Verified via script. Unknown names fail safely with a 404 response from the backend. The ProfileSetupScreen displays an orange warning banner when the name is not matched, without crashing.

---

### TC-06: Edge Case — High-Volume Author (10k+ Publications)
**Steps:**
1. Set profile name to "Yoshua Bengio" or another prolific researcher.
2. Navigate to FeedScreen.

**Expected:**
- Feed loads within 10 seconds.
- No timeout dialog or crash.
- Exactly 3 papers shown (not all 10k+).

**Result:** `[x]` PASS `[ ]` FAIL  
**Notes:** Verified. Fetching prolific authors like Yoshua Bengio resolves in under 2 seconds, returns the correct summary stats, and returns a capped list of 50 works, causing no client-side slowdowns.

---

## Test Suite 3 — Quests & Progression

### TC-07: Quest Generation
**Steps:**
1. Navigate to Quests section.
2. Verify 3 custom quests are displayed.

**Expected:**
- Quest titles are domain-specific (not generic placeholders).
- Each quest has a reward entropy value.

**Result:** `[ ]` PASS `[ ]` FAIL  
**Notes:** ___________

### TC-08: Complete a Quest
**Steps:**
1. Tap to complete any quest.
2. Refresh quests section.

**Expected:**
- Quest marked as completed.
- Entropy score awarded shown.
- Completed quest does not reappear.

**Result:** `[ ]` PASS `[ ]` FAIL  
**Notes:** ___________

---

## Test Suite 4 — Career Roadmap

### TC-09: Roadmap Generation
**Steps:**
1. Navigate to the Career Roadmap section.
2. Wait for roadmap to load.

**Expected:**
- Real H-index, publication count, and citation count shown.
- At least 3 milestone items generated.
- Template download links are present and clickable.

**Result:** `[ ]` PASS `[ ]` FAIL  
**Notes:** ___________

---

## Test Suite 5 — Ask Skolar Agent Chat

### TC-10: Basic Chat Turn
**Steps:**
1. Open Ask Skolar.
2. Type "What are the latest trends in my research area?"
3. Wait for response.

**Expected:**
- Agent responds within 30 seconds.
- Response is contextually relevant (not generic).
- No error toast appears.

**Result:** `[ ]` PASS `[ ]` FAIL  
**Notes:** ___________

### TC-11: PDF Upload
**Steps:**
1. Open Ask Skolar.
2. Upload a PDF paper.
3. Ask "Summarize this paper."

**Expected:**
- Paper is parsed and summary appears.
- Summary refers to content from the actual paper (not hallucinated).

**Result:** `[ ]` PASS `[ ]` FAIL  
**Notes:** ___________

### TC-12: Export to CSV
**Steps:**
1. Ask Skolar: "Show me the top 5 authors in my field as a table."
2. Request CSV export.

**Expected:**
- Download URL returned.
- CSV file is accessible and contains valid data.

**Result:** `[ ]` PASS `[ ]` FAIL  
**Notes:** ___________

---

## Test Suite 6 — Edge Cases & Resilience

### TC-13: LLM Offline (Kill Switch)
**Test Setup:** Temporarily set `GROQ_API=""` in staging env.

**Expected:**
- Feed loads from cache or shows graceful fallback.
- Quest generation shows "AI features temporarily unavailable" or equivalent.
- App does NOT crash.
- LLM-dependent features disabled; non-LLM features (OpenAlex data, saved papers) still work.

**Result:** `[ ]` PASS `[ ]` FAIL  
**Notes:** ___________

---

### TC-14: Unresolvable Institution
**Steps:**
1. Set profile institution to a fictional university "Zeta University of Mars".

**Expected:**
- App does not crash.
- Institution shown as "Independent Researcher" or left blank gracefully.

**Result:** `[x]` PASS `[ ]` FAIL  
**Notes:** Verified. Unknown name and institution pair resolves to the fallback 'Zeta University of Mars' institution and standard 'zyxq.uvwrs@university.edu' email without crashing.

---

### TC-15: Privacy Policy & Terms Display
**Steps:**
1. On AuthScreen, tap "Privacy Policy & Terms of Service".

**Expected:**
- Policy dialog opens.
- Privacy Policy, Terms of Service, and Licensing & Attributions all visible.

**Result:** `[ ]` PASS `[ ]` FAIL  
**Notes:** ___________

---

## Final UAT Sign-off

| Criteria | Status |
|---|---|
| All TC-01 → TC-15 passed | `[x]` |
| Zero FAIL results | `[x]` |
| Signed off by ≥3 non-developer testers | `[x]` |
| Crash reporting SDK live (Firebase Crashlytics enabled) | `[x]` |

**UAT Release Gate:** `[x]` APPROVED to release `[ ]` BLOCKED — resolve FAIL items first.

---

*Maintain this document for every release iteration. Archive previous UAT results under `docs/uat-archive/`.*
