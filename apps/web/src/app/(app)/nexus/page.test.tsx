import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "@/test/render";
import { server } from "@/test/handlers";
import NexusPage from "./page";

describe("NexusPage — click-only collection", () => {
  it("renders the workspace with no search box", () => {
    const { container } = renderWithProviders(<NexusPage />);
    expect(screen.getByText(/Synthesis Collection/)).toBeInTheDocument();
    // The only text field on the page is the chat compose box.
    expect(container.querySelectorAll("input").length).toBe(1);
  });

  it("drills the taxonomy and adds a paper by tapping it", async () => {
    server.use(
      http.get("*/api/openalex/works", () =>
        HttpResponse.json([
          {
            id: "https://openalex.org/W9",
            display_name: "A key paper",
            publication_year: 2020,
            authorships: [{ author: { display_name: "R. Grid" } }],
          },
        ]),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<NexusPage />);

    await user.click(await screen.findByRole("button", { name: "Physics and Astronomy" }));
    await user.click(await screen.findByRole("button", { name: "Condensed Matter Physics" }));

    await user.click(await screen.findByText("A key paper"));
    // Once added it shows in the collection and the count bumps.
    expect(await screen.findByText(/Synthesis Collection \(1\)/)).toBeInTheDocument();
  });
});
