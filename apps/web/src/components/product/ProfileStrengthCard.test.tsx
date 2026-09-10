import { describe, expect, it } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { ProfileStrengthCard } from "./ProfileStrengthCard";
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
    ...over,
  }) as unknown as AuthorResponse;

describe("ProfileStrengthCard", () => {
  it("shows the strength meter and a 'Do next' action pointing at the profile", () => {
    renderWithProviders(
      <ProfileStrengthCard name="Ada Lovelace" firestoreProfile={profile()} author={null} unresolved />,
    );
    expect(screen.getByText("Profile strength")).toBeInTheDocument();
    // name only (10%) -> Beginner
    expect(screen.getByText("Beginner")).toBeInTheDocument();
    const action = screen.getByRole("link", { name: /Link your OpenAlex or ORCID/i });
    expect(action).toHaveAttribute("href", "/profile");
  });

  it("renders real OpenAlex reach numbers once the author resolves", () => {
    renderWithProviders(
      <ProfileStrengthCard
        name="Ada Lovelace"
        firestoreProfile={profile({ researchFocus: "Computing", academicStatus: "Professor", about: "First programmer." })}
        author={author()}
      />,
    );
    expect(screen.getByText("Your work on OpenAlex")).toBeInTheDocument();
    expect(screen.getByText("Citations")).toBeInTheDocument();
    expect(screen.getByText("h-index")).toBeInTheDocument();
  });

  it("does not invent reach numbers when the profile is unresolved", () => {
    renderWithProviders(
      <ProfileStrengthCard name="Ada Lovelace" firestoreProfile={profile()} author={null} unresolved />,
    );
    expect(screen.queryByText("Your work on OpenAlex")).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Link your published work/i })).toBeInTheDocument();
  });

  it("shows the complete state with no action list at 100%", () => {
    renderWithProviders(
      <ProfileStrengthCard
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

  it("renders a skeleton while loading", () => {
    const { container } = renderWithProviders(
      <ProfileStrengthCard firestoreProfile={null} author={null} loading />,
    );
    expect(container.querySelector(".animate-pulse")).toBeInTheDocument();
    expect(screen.queryByText("Profile strength")).not.toBeInTheDocument();
  });
});
