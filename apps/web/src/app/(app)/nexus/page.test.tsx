import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "@/test/render";
import { server } from "@/test/handlers";
import NexusPage from "./page";

describe("NexusPage", () => {
  it("renders the Nexus workspace", () => {
    renderWithProviders(<NexusPage />);
    expect(screen.getByPlaceholderText(/search literature/i)).toBeInTheDocument();
  });

  it("searches OpenAlex via useQuery once the query passes 3 chars", async () => {
    server.use(
      http.get("*/api/openalex/works", () =>
        HttpResponse.json([
          {
            id: "https://openalex.org/W1",
            display_name: "Attention is all you need",
            publication_year: 2017,
          },
        ]),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<NexusPage />);
    await user.type(screen.getByPlaceholderText(/search literature/i), "attention");
    expect(await screen.findByText("Attention is all you need")).toBeInTheDocument();
  });

  it("adding a search result to the collection clears the search box", async () => {
    server.use(
      http.get("*/api/openalex/works", () =>
        HttpResponse.json([
          { id: "https://openalex.org/W9", display_name: "A key paper", publication_year: 2020 },
        ]),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<NexusPage />);
    const box = screen.getByPlaceholderText(/search literature/i);
    await user.type(box, "a key paper");
    await user.click(await screen.findByText("A key paper"));
    expect(box).toHaveValue("");
  });
});
