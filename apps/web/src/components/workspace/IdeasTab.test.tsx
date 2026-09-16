import { describe, expect, it, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "@/test/render";
import { IdeasTab } from "./IdeasTab";

describe("IdeasTab", () => {
  it("captures ideas separately and inserts them only on request", async () => {
    const user = userEvent.setup();
    const onInsertIdea = vi.fn();
    renderWithProviders(<IdeasTab onInsertIdea={onInsertIdea} />);
    await user.type(screen.getByLabelText("New idea"), "Test the competing hypothesis");
    await user.click(screen.getByRole("button", { name: "Capture idea" }));
    expect(screen.getByText("Test the competing hypothesis")).toBeInTheDocument();
    expect(onInsertIdea).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Insert into draft" }));
    expect(onInsertIdea).toHaveBeenCalledWith("\n\n> Idea: Test the competing hypothesis\n");
  });
});
