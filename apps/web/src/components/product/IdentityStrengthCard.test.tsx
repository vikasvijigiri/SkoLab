import { describe, expect, it } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { IdentityStrengthCard } from "./IdentityStrengthCard";
import type { AuthorResponse, SkoLabUser } from "@/lib/types";

const profile = (over: Partial<SkoLabUser> = {}): SkoLabUser =>
  ({
    uid: "u1",
    name: "Ada Lovelace",
    researchFocus: "",
    academicStatus: "Researcher",
    about: "",
    openAlexId: "",
    ...over,
  }) as SkoLabUser;

const author = (over: Partial<AuthorResponse> = {}): AuthorResponse =>
  ({
    id: "A1",
    display_name: "Ada Lovelace",
    h_index: 42,
    i10_index: 60,
    works_count: 120,
    cited_by_count: 9001,
    expertise: ["Computing"],
    skills: [],
    disruption_score: 61,
    average_skill_score: 74,
    ...over,
  }) as unknown as AuthorResponse;

describe("IdentityStrengthCard", () => {
  it("shows the identity header and initial even before any metrics resolve", () => {
    renderWithProviders(
      <IdentityStrengthCard
        name="Ada Lovelace"
        status="Postdoc"
        firestoreProfile={profile()}
        author={null}
        unresolved
      />,
    );
    expect(screen.getByText("Ada Lovelace")).toBeInTheDocument();
    expect(screen.getByText("Postdoc")).toBeInTheDocument();
    expect(screen.getByText("A")).toBeInTheDocument();
    expect(screen.getByText(/Add your name or ORCID/i)).toBeInTheDocument();
  });

  it("shows the Disruption/Skill/Works trio once the author resolves", () => {
    renderWithProviders(
      <IdentityStrengthCard
        name="Ada Lovelace"
        status="Postdoc"
        firestoreProfile={profile()}
        author={author()}
      />,
    );
    expect(screen.getByText("Disruption")).toBeInTheDocument();
    expect(screen.getByText("Skill")).toBeInTheDocument();
    expect(screen.getByText("Works")).toBeInTheDocument();
  });

  it("shows the strength meter and a 'Do next' action pointing at the profile", () => {
    renderWithProviders(
      <IdentityStrengthCard name="Ada Lovelace" firestoreProfile={profile()} author={null} unresolved />,
    );
    expect(screen.getByText("Profile strength")).toBeInTheDocument();
    expect(screen.getByText("Beginner")).toBeInTheDocument();
    const action = screen.getByRole("link", { name: /Link your OpenAlex or ORCID/i });
    expect(action).toHaveAttribute("href", "/profile");
  });

  it("shows Citations and h-index once resolved, without repeating Works", () => {
    renderWithProviders(
      <IdentityStrengthCard
        name="Ada Lovelace"
        firestoreProfile={profile({ researchFocus: "Computing", academicStatus: "Professor", about: "First programmer." })}
        author={author()}
      />,
    );
    expect(screen.getByText("Your work on OpenAlex")).toBeInTheDocument();
    expect(screen.getByText("Citations")).toBeInTheDocument();
    expect(screen.getByText("h-index")).toBeInTheDocument();
    // "Works" appears exactly once (the identity trio), not a second time in the reach row
    expect(screen.getAllByText("Works")).toHaveLength(1);
  });

  it("does not invent reach numbers when the profile is unresolved", () => {
    renderWithProviders(
      <IdentityStrengthCard name="Ada Lovelace" firestoreProfile={profile()} author={null} unresolved />,
    );
    expect(screen.queryByText("Your work on OpenAlex")).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Link your published work/i })).toBeInTheDocument();
  });

  it("shows the complete state with no action list at 100%", () => {
    renderWithProviders(
      <IdentityStrengthCard
        name="Ada Lovelace"
        firestoreProfile={profile({
          researchFocus: "Computing",
          academicStatus: "Professor",
          about: "First programmer.",
          openAlexId: "A1",
        })}
        author={author()}
      />,
    );
    expect(screen.getByText(/All-Star/)).toBeInTheDocument();
    expect(screen.getByText(/Your profile is complete/i)).toBeInTheDocument();
    expect(screen.queryByText("Do next")).not.toBeInTheDocument();
  });

  it("renders a single skeleton while loading", () => {
    const { container } = renderWithProviders(
      <IdentityStrengthCard name="Ada Lovelace" firestoreProfile={null} author={null} loading />,
    );
    expect(container.querySelectorAll(".animate-pulse")).toHaveLength(1);
    expect(screen.queryByText("Profile strength")).not.toBeInTheDocument();
  });
});
