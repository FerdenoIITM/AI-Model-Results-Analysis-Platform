import { act, render, renderHook, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";
import { PASSWORD } from "../../test/fixtures";
import { API, server, tokenFor } from "../../test/server";
import { AuthProvider, useAuth } from "./AuthContext";

const wrapper = ({ children }: { children: ReactNode }) => <AuthProvider>{children}</AuthProvider>;

describe("AuthContext", () => {
  it("finishes loading with no user when there is no stored token", async () => {
    const { result } = renderHook(() => useAuth(), { wrapper });
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.user).toBeNull();
    expect(result.current.token).toBeNull();
  });

  it("restores the session from a stored token", async () => {
    localStorage.setItem("amrap_token", tokenFor("u-admin"));
    const { result } = renderHook(() => useAuth(), { wrapper });
    expect(result.current.loading).toBe(true);
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.user).toMatchObject({ id: "u-admin", role: "admin" });
    expect(result.current.token).toBe(tokenFor("u-admin"));
  });

  it("discards a stored token the server rejects", async () => {
    localStorage.setItem("amrap_token", "expired-or-forged");
    const { result } = renderHook(() => useAuth(), { wrapper });
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.user).toBeNull();
    expect(result.current.token).toBeNull();
    expect(localStorage.getItem("amrap_token")).toBeNull();
  });

  it("discards the token when the server cannot be reached", async () => {
    server.use(http.get(`${API}/auth/me`, () => HttpResponse.error()));
    localStorage.setItem("amrap_token", tokenFor("u-super"));
    const { result } = renderHook(() => useAuth(), { wrapper });
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.user).toBeNull();
    expect(localStorage.getItem("amrap_token")).toBeNull();
  });

  it("stores the token and user after a successful login", async () => {
    const { result } = renderHook(() => useAuth(), { wrapper });
    await waitFor(() => expect(result.current.loading).toBe(false));
    await act(() => result.current.login("member@amrap.test", PASSWORD));
    expect(result.current.user).toMatchObject({ id: "u-member", role: "member" });
    expect(localStorage.getItem("amrap_token")).toBe(tokenFor("u-member"));
  });

  it("leaves the session empty when login fails", async () => {
    const { result } = renderHook(() => useAuth(), { wrapper });
    await waitFor(() => expect(result.current.loading).toBe(false));
    await expect(result.current.login("member@amrap.test", "wrong")).rejects.toThrow("Invalid email or password");
    expect(result.current.user).toBeNull();
    expect(localStorage.getItem("amrap_token")).toBeNull();
  });

  it("clears the token and user on logout", async () => {
    localStorage.setItem("amrap_token", tokenFor("u-super"));
    const { result } = renderHook(() => useAuth(), { wrapper });
    await waitFor(() => expect(result.current.user).not.toBeNull());
    act(() => result.current.logout());
    expect(result.current.user).toBeNull();
    expect(result.current.token).toBeNull();
    expect(localStorage.getItem("amrap_token")).toBeNull();
  });

  it("throws a clear error when used outside the provider", () => {
    function Orphan() {
      useAuth();
      return null;
    }
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});
    expect(() => render(<Orphan />)).toThrow("useAuth must be used within AuthProvider");
    spy.mockRestore();
  });
});
