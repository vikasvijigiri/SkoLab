import { describe, expect, it, vi, beforeEach } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "@/test/render";
import { ShareModal } from "./ShareModal";
import type { CollabProject } from "@/lib/types";

const auth = vi.hoisted(() => ({
  user: { uid: "owner-uid", displayName: "Ada Lovelace" } as { uid: string; displayName: string } | null,
}));
vi.mock("@/lib/hooks/AuthProvider", () => ({
  useAuth: () => ({ user: auth.user, getIdToken: vi.fn().mockResolvedValue("token") }),
}));

const workspace = vi.hoisted(() => ({
  findResearcherByEmail: vi.fn(),
  inviteMember: vi.fn(),
}));
vi.mock("@/lib/firebase/workspace", async () => {
  const actual = await vi.importActual<typeof import("@/lib/firebase/workspace")>("@/lib/firebase/workspace");
  return {
    ...actual,
    findResearcherByEmail: workspace.findResearcherByEmail,
    inviteMember: workspace.inviteMember,
  };
});

const inbox = vi.hoisted(() => ({ writeInboxEvent: vi.fn().mockResolvedValue(undefined) }));
vi.mock("@/lib/firebase/inbox", () => ({ writeInboxEvent: inbox.writeInboxEvent }));

vi.mock("@/lib/api/endpoints", () => ({ logPeerInvite: vi.fn().mockResolvedValue(undefined) }));

function project(): CollabProject {
  return {
    id: "p1",
    name: "Quantum foam",
    description: "",
    ownerUid: "owner-uid",
    ownerName: "Ada Lovelace",
    members: [{ uid: "owner-uid", name: "Ada Lovelace", email: "ada@x.edu", role: "owner" }],
    memberUids: ["owner-uid"],
    recentEquations: "",
    manuscriptProgress: 0,
    manuscriptDraft: "",
  };
}

describe("ShareModal invite flow", () => {
  beforeEach(() => {
    workspace.findResearcherByEmail.mockReset();
    workspace.inviteMember.mockReset().mockResolvedValue(undefined);
    inbox.writeInboxEvent.mockClear();
  });

  it("writes an invite inbox event for the invited researcher after a successful invite", async () => {
    workspace.findResearcherByEmail.mockResolvedValue({
      uid: "invitee-uid",
      name: "Grace Hopper",
      email: "grace@x.edu",
      phone: "",
    });
    const user = userEvent.setup();
    renderWithProviders(<ShareModal project={project()} open onClose={vi.fn()} />);

    await user.type(screen.getByPlaceholderText(/colleague@university.edu/i), "grace@x.edu");
    await user.click(screen.getByRole("button", { name: /^invite$/i }));

    await screen.findByText(/added as editor/i);
    expect(inbox.writeInboxEvent).toHaveBeenCalledWith({
      toUid: "invitee-uid",
      type: "invite",
      actor: { id: "owner-uid", display_name: "Ada Lovelace" },
      why: "Quantum foam",
      href: "/workspace/p1",
    });
  });

  it("does not write an inbox event when no SkoLab account exists for the email", async () => {
    workspace.findResearcherByEmail.mockResolvedValue(null);
    const user = userEvent.setup();
    renderWithProviders(<ShareModal project={project()} open onClose={vi.fn()} />);

    await user.type(screen.getByPlaceholderText(/colleague@university.edu/i), "nobody@x.edu");
    await user.click(screen.getByRole("button", { name: /^invite$/i }));

    await screen.findByText(/an invite was logged/i);
    expect(inbox.writeInboxEvent).not.toHaveBeenCalled();
  });
});
