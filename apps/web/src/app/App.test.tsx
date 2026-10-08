import { screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { PASSWORD } from "../test/fixtures";
import { API, server } from "../test/server";
import { renderApp } from "../test/render";

function nav() {
  return within(screen.getByRole("navigation"));
}

function expectActive(name: string) {
  const links = nav().getAllByRole("link");
  for (const link of links) {
    if (link.textContent === name) {
      expect(link).toHaveClass("active");
      expect(link).toHaveAttribute("aria-current", "page");
    } else {
      expect(link).not.toHaveClass("active");
      expect(link).not.toHaveAttribute("aria-current");
    }
  }
}

describe("routing and access control", () => {
  it("shows a loading state while the session is being restored", async () => {
    server.use(
      http.get(`${API}/auth/me`, async () => {
        await new Promise((resolve) => setTimeout(resolve, 100));
        return HttpResponse.json({ message: "nope" }, { status: 401 });
      }),
    );
    renderApp("/results", { as: "member" });
    expect(screen.getByText("Loading…")).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "Sign in" })).toBeInTheDocument();
  });

  it.each(["/", "/results", "/chat", "/users"])("redirects anonymous visitors from %s to the login page", async (path) => {
    renderApp(path);
    expect(await screen.findByRole("button", { name: "Sign in" })).toBeInTheDocument();
    expect(window.location.pathname).toBe("/login");
  });

  it("sends a signed-in user away from the login page", async () => {
    renderApp("/login", { as: "member" });
    expect(await screen.findByRole("heading", { name: "Dashboard" })).toBeInTheDocument();
    expect(window.location.pathname).toBe("/");
  });

  it("redirects unknown routes to the dashboard", async () => {
    renderApp("/does/not/exist", { as: "member" });
    expect(await screen.findByRole("heading", { name: "Dashboard" })).toBeInTheDocument();
    expect(window.location.pathname).toBe("/");
  });

  it("keeps members out of user management", async () => {
    renderApp("/users", { as: "member" });
    expect(await screen.findByRole("heading", { name: "Dashboard" })).toBeInTheDocument();
    expect(window.location.pathname).toBe("/");
    expect(screen.queryByRole("heading", { name: "User Management" })).toBeNull();
  });

  it.each(["admin", "superadmin"] as const)("lets %s open user management", async (role) => {
    renderApp("/users", { as: role });
    expect(await screen.findByRole("heading", { name: "User Management" })).toBeInTheDocument();
  });
});

describe("navigation", () => {
  it.each([
    ["/", "Dashboard"],
    ["/results", "Results"],
    ["/chat", "Chat"],
    ["/users", "User Management"],
  ])("highlights only the active link at %s", async (path, name) => {
    renderApp(path, { as: "superadmin" });
    await screen.findByRole("navigation");
    await waitFor(() => expectActive(name));
  });

  it("moves the highlight when the user navigates", async () => {
    const { user } = renderApp("/", { as: "admin" });
    await screen.findByRole("navigation");
    expectActive("Dashboard");
    await user.click(nav().getByRole("link", { name: "Results" }));
    expect(await screen.findByRole("heading", { name: "Model Results" })).toBeInTheDocument();
    expectActive("Results");
    await user.click(nav().getByRole("link", { name: "User Management" }));
    expect(await screen.findByRole("heading", { name: "User Management" })).toBeInTheDocument();
    expectActive("User Management");
    await user.click(nav().getByRole("link", { name: "Dashboard" }));
    expectActive("Dashboard");
  });

  it.each([
    ["superadmin", true],
    ["admin", true],
    ["member", false],
  ] as const)("shows the User Management link to %s: %s", async (role, visible) => {
    renderApp("/", { as: role });
    await screen.findByRole("navigation");
    expect(nav().queryByRole("link", { name: "User Management" }) !== null).toBe(visible);
    expect(nav().getAllByRole("link").map((l) => l.textContent)).toEqual(
      visible ? ["Dashboard", "Results", "Chat", "User Management"] : ["Dashboard", "Results", "Chat"],
    );
  });

  it("shows who is signed in and logs out", async () => {
    const { user } = renderApp("/results", { as: "admin" });
    expect(await screen.findByText(/Alice Admin/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Log out" }));
    expect(await screen.findByRole("button", { name: "Sign in" })).toBeInTheDocument();
    expect(window.location.pathname).toBe("/login");
    expect(localStorage.getItem("amrap_token")).toBeNull();
  });
});

describe("login page", () => {
  async function fill(user: ReturnType<typeof renderApp>["user"], email: string, password: string) {
    await user.type(await screen.findByLabelText("Email"), email);
    await user.type(screen.getByLabelText("Password"), password);
    await user.click(screen.getByRole("button", { name: "Sign in" }));
  }

  it("signs in and lands on the dashboard", async () => {
    const { user } = renderApp("/login");
    await fill(user, "admin@amrap.test", PASSWORD);
    expect(await screen.findByRole("heading", { name: "Dashboard" })).toBeInTheDocument();
    expect(window.location.pathname).toBe("/");
  });

  it("shows the server message for wrong credentials and stays on the page", async () => {
    const { user } = renderApp("/login");
    await fill(user, "admin@amrap.test", "wrong-password");
    expect(await screen.findByText("Invalid email or password")).toBeInTheDocument();
    expect(window.location.pathname).toBe("/login");
    expect(screen.getByRole("button", { name: "Sign in" })).toBeEnabled();
  });

  it("shows the deactivated-account message", async () => {
    server.use(http.post(`${API}/auth/login`, () => HttpResponse.json({ message: "Account is deactivated" }, { status: 403 })));
    const { user } = renderApp("/login");
    await fill(user, "member@amrap.test", PASSWORD);
    expect(await screen.findByText("Account is deactivated")).toBeInTheDocument();
  });

  it("shows an error when the server is unreachable", async () => {
    server.use(http.post(`${API}/auth/login`, () => HttpResponse.error()));
    const { user } = renderApp("/login");
    await fill(user, "member@amrap.test", PASSWORD);
    expect(await screen.findByText(/fetch/i)).toHaveClass("error");
  });

  it("disables the button while signing in", async () => {
    let release: () => void = () => {};
    server.use(
      http.post(`${API}/auth/login`, async () => {
        await new Promise<void>((resolve) => (release = resolve));
        return HttpResponse.json({ message: "Invalid email or password" }, { status: 401 });
      }),
    );
    const { user } = renderApp("/login");
    await fill(user, "member@amrap.test", PASSWORD);
    expect(await screen.findByRole("button", { name: "Signing in…" })).toBeDisabled();
    release();
    expect(await screen.findByRole("button", { name: "Sign in" })).toBeEnabled();
  });

  it("requires both fields before submitting", async () => {
    renderApp("/login");
    expect(await screen.findByLabelText("Email")).toBeRequired();
    expect(screen.getByLabelText("Email")).toHaveAttribute("type", "email");
    expect(screen.getByLabelText("Password")).toBeRequired();
    expect(screen.getByLabelText("Password")).toHaveAttribute("type", "password");
  });
});

describe("dashboard", () => {
  it("shows the signed-in user's name, email and role", async () => {
    renderApp("/", { as: "member" });
    const heading = await screen.findByRole("heading", { name: "Dashboard" });
    const panel = within(heading.closest("section")!);
    expect(panel.getByText("Mia Member")).toBeInTheDocument();
    expect(panel.getByText(/member@amrap\.test/)).toBeInTheDocument();
    expect(panel.getByRole("link", { name: "Model Results" })).toHaveAttribute("href", "/results");
    expect(panel.getByRole("link", { name: "Ask AMRAP" })).toHaveAttribute("href", "/chat");
    expect(panel.queryByRole("link", { name: "User Management" })).toBeNull();
  });

  it("links admins to user management", async () => {
    renderApp("/", { as: "admin" });
    const heading = await screen.findByRole("heading", { name: "Dashboard" });
    expect(within(heading.closest("section")!).getByRole("link", { name: "User Management" })).toHaveAttribute(
      "href",
      "/users",
    );
  });

  it("navigates to results from the dashboard link", async () => {
    const { user } = renderApp("/", { as: "member" });
    const heading = await screen.findByRole("heading", { name: "Dashboard" });
    await user.click(within(heading.closest("section")!).getByRole("link", { name: "Model Results" }));
    expect(await screen.findByRole("heading", { name: "Model Results" })).toBeInTheDocument();
  });
});
