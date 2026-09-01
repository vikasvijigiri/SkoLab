import { describe, expect, it } from "vitest";
import { authorQuery, collaboratorsQuery, authorSuggestionsQuery } from "./queries";
import { makeAuthorResponse } from "@/test/fixtures";
import { server } from "@/test/handlers";
import { http, HttpResponse } from "msw";

const API = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

describe("query option factories", () => {
  it("authorQuery builds the documented key and stays disabled with no name/id", () => {
    expect(authorQuery("Ada", "A1", "CS").queryKey).toEqual([
      "author",
      { id: "A1", name: "Ada", focus: "CS" },
    ]);
    expect(authorQuery("").enabled).toBe(false);
    expect(authorQuery("Ada").enabled).toBe(true);
  });

  it("collaboratorsQuery scopes its key under the author id", () => {
    const key = collaboratorsQuery("A1", "Physics").queryKey;
    expect(key[0]).toBe("author");
    expect(key[1]).toBe("A1");
    expect(key[2]).toBe("collaborators");
  });

  it("authorQuery.queryFn calls /search_author and returns the payload", async () => {
    const expected = makeAuthorResponse({ display_name: "Grace Hopper" });
    server.use(http.get(`${API}/search_author`, () => HttpResponse.json(expected)));
    const data = await authorQuery("Grace Hopper", "A9").queryFn!({} as never);
    expect(data.display_name).toBe("Grace Hopper");
  });

  it("authorSuggestionsQuery is disabled for short input", () => {
    expect(authorSuggestionsQuery("a").enabled).toBe(false);
    expect(authorSuggestionsQuery("ada").enabled).toBe(true);
  });
});
