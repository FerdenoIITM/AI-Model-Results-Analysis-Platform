import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactElement } from "react";
import { MemoryRouter } from "react-router-dom";
import { App } from "../app/App";
import { AuthProvider } from "../features/auth/AuthContext";
import type { UserRole } from "../shared/api/types";
import { userId } from "./fixtures";
import { tokenFor } from "./server";

export function signInAs(role: UserRole) {
  localStorage.setItem("amrap_token", tokenFor(userId(role)));
}

/** Renders the real application (routes, auth bootstrap, shell) at the given path. */
export function renderApp(path: string, options: { as?: UserRole } = {}) {
  if (options.as) signInAs(options.as);
  window.history.replaceState({}, "", path);
  const user = userEvent.setup();
  return { user, ...render(<App />) };
}

/** Renders a single component inside the auth provider and an in-memory router. */
export function renderWithProviders(ui: ReactElement, options: { route?: string; as?: UserRole } = {}) {
  if (options.as) signInAs(options.as);
  const user = userEvent.setup();
  return {
    user,
    ...render(
      <AuthProvider>
        <MemoryRouter initialEntries={[options.route ?? "/"]}>{ui}</MemoryRouter>
      </AuthProvider>,
    ),
  };
}

/** Body rows of the results table as arrays of cell text. */
export function tableRows(): string[][] {
  const table = screen.getByRole("table");
  const [, body] = within(table).getAllByRole("rowgroup");
  return within(body)
    .queryAllByRole("row")
    .map((row) => within(row).queryAllByRole("cell").map((cell) => cell.textContent ?? ""));
}
