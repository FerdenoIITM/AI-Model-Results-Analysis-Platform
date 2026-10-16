import { screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { renderApp } from "../../test/render";
import { API, db, server } from "../../test/server";

async function openChat() {
  const rendered = renderApp("/chat", { as: "member" });
  await screen.findByRole("heading", { name: "Ask AMRAP" });
  return rendered;
}

function input() {
  return screen.getByPlaceholderText("Ask a question about your results…");
}

describe("ChatPage", () => {
  it("shows suggestions and a disabled Send button before anything is typed", async () => {
    const { user } = await openChat();
    expect(screen.getByText("Try one of these to get started:")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Send" })).toBeDisabled();
    await user.type(input(), "   ");
    expect(screen.getByRole("button", { name: "Send" })).toBeDisabled();
  });

  it("sends a question and shows the answer without sources", async () => {
    const { user } = await openChat();
    await user.type(input(), "  Which model scored best?  {Enter}");
    expect(await screen.findByText("CardioNet 1.0.0 had the highest confidence (97.5).")).toBeInTheDocument();
    expect(screen.getByText("Which model scored best?")).toHaveClass("chat-bubble-text");
    expect(db.chatRequests).toEqual([{ question: "Which model scored best?" }]);
    expect(input()).toHaveValue("");
    expect(screen.queryByText(/Sources/)).toBeNull();
    expect(screen.queryByText("Try one of these to get started:")).toBeNull();
  });

  it("sends selected model, category and sub category filters", async () => {
    const { user } = await openChat();

    function multi(label: string) {
      const root = screen.getByText(label, { selector: ".multi-select-label" }).closest(".multi-select") as HTMLElement;
      return within(root);
    }

    async function pick(label: string, option: string) {
      const m = multi(label);
      const trigger = m.getByRole("button", { name: label });
      if (trigger.getAttribute("aria-expanded") !== "true") {
        await user.click(trigger);
      }
      await user.click(await m.findByRole("checkbox", { name: option }));
      await user.keyboard("{Escape}");
    }

    await pick("Model", "CardioNet");
    await pick("Category", "Cardiology");
    await pick("Sub Category", "Aortic Valve");
    await user.type(input(), "What ran?");
    await user.click(screen.getByRole("button", { name: "Send" }));
    await waitFor(() => expect(db.chatRequests).toHaveLength(1));
    expect(db.chatRequests[0]).toEqual({
      question: "What ran?",
      modelNames: ["CardioNet"],
      categories: ["Cardiology"],
      subCategories: ["Aortic Valve"],
    });
  });

  it("includes the earlier conversation as history", async () => {
    const { user } = await openChat();
    await user.type(input(), "First question{Enter}");
    await screen.findByText("CardioNet 1.0.0 had the highest confidence (97.5).");
    await user.type(input(), "And the second?{Enter}");
    await waitFor(() => expect(db.chatRequests).toHaveLength(2));
    expect(db.chatRequests[1]).toEqual({
      question: "And the second?",
      history: [
        { role: "user", text: "First question" },
        { role: "assistant", text: "CardioNet 1.0.0 had the highest confidence (97.5)." },
      ],
    });
  });

  it("shows the server error as an error message", async () => {
    server.use(
      http.post(`${API}/chat`, () =>
        HttpResponse.json({ message: "The language model is unavailable. Try again later." }, { status: 502 }),
      ),
    );
    const { user } = await openChat();
    await user.type(input(), "Hello?{Enter}");
    const bubble = await screen.findByText("The language model is unavailable. Try again later.");
    expect(bubble.closest(".chat-bubble")).toHaveClass("error");
    expect(screen.getByRole("button", { name: "Send" })).toBeDisabled();
    expect(input()).toBeEnabled();
  });

  it("disables input while waiting for the answer", async () => {
    let release: () => void = () => {};
    server.use(
      http.post(`${API}/chat`, async () => {
        await new Promise<void>((resolve) => (release = resolve));
        return HttpResponse.json({ answer: "done", citations: [] });
      }),
    );
    const { user } = await openChat();
    await user.type(input(), "Slow question{Enter}");
    expect(await screen.findByRole("button", { name: "Thinking…" })).toBeDisabled();
    expect(input()).toBeDisabled();
    expect(screen.getByRole("button", { name: "Clear" })).toBeDisabled();
    release();
    expect(await screen.findByText("done")).toBeInTheDocument();
    expect(screen.queryByText(/Sources/)).toBeNull();
  });

  it("clears the conversation", async () => {
    const { user } = await openChat();
    await user.type(input(), "Hi{Enter}");
    await screen.findByText("CardioNet 1.0.0 had the highest confidence (97.5).");
    await user.click(screen.getByRole("button", { name: "Clear" }));
    expect(screen.queryByText("Hi")).toBeNull();
    expect(screen.getByText("Try one of these to get started:")).toBeInTheDocument();
  });

  it("keeps the conversation when navigating away and back", async () => {
    const { user } = await openChat();
    await user.type(input(), "Remember me{Enter}");
    await screen.findByText("CardioNet 1.0.0 had the highest confidence (97.5).");
    await user.click(within(screen.getByRole("navigation")).getByRole("link", { name: "Results" }));
    expect(await screen.findByRole("heading", { name: "Model Results" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Ask AMRAP" })).toBeNull();
    await user.click(within(screen.getByRole("navigation")).getByRole("link", { name: "Chat" }));
    expect(await screen.findByText("Remember me")).toBeVisible();
  });
});
