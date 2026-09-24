import { useEffect, useMemo, useState, type FormEvent } from "react";
import { useAuth } from "../../features/auth/AuthContext";
import {
  createUser,
  deactivateUser,
  listUsers,
  updateUser,
} from "../../shared/api/client";
import type { UserRole, UserSummary } from "../../shared/api/types";

export function UsersPage() {
  const { token, user } = useAuth();
  const [users, setUsers] = useState<UserSummary[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [role, setRole] = useState<UserRole>("member");

  const roleOptions = useMemo(() => {
    if (user?.role === "superadmin") {
      return ["admin", "member"] as UserRole[];
    }
    return ["member"] as UserRole[];
  }, [user]);

  async function refresh() {
    if (!token) return;
    setLoading(true);
    setError(null);
    try {
      setUsers(await listUsers(token));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load users");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  async function onCreate(e: FormEvent) {
    e.preventDefault();
    if (!token) return;
    setError(null);
    try {
      await createUser(token, { email, password, displayName, role });
      setEmail("");
      setPassword("");
      setDisplayName("");
      setRole("member");
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Create failed");
    }
  }

  async function onDeactivate(id: string) {
    if (!token) return;
    try {
      await deactivateUser(token, id);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Deactivate failed");
    }
  }

  async function onActivate(id: string) {
    if (!token) return;
    try {
      await updateUser(token, id, { active: true });
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Activate failed");
    }
  }

  async function onToggleRole(target: UserSummary) {
    if (!token || user?.role !== "superadmin") return;
    if (target.role === "superadmin") return;
    const next: UserRole = target.role === "admin" ? "member" : "admin";
    try {
      await updateUser(token, target.id, { role: next });
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Role change failed");
    }
  }

  return (
    <section className="panel">
      <h1>User Management</h1>
      {error ? <p className="error">{error}</p> : null}

      <form className="create-form" onSubmit={onCreate}>
        <h2>Create user</h2>
        <div className="grid">
          <label>
            Display name
            <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} required />
          </label>
          <label>
            Email
            <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
          </label>
          <label>
            Password
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              minLength={8}
              required
            />
          </label>
          <label>
            Role
            <select value={role} onChange={(e) => setRole(e.target.value as UserRole)}>
              {roleOptions.map((r) => (
                <option key={r} value={r}>
                  {r}
                </option>
              ))}
            </select>
          </label>
        </div>
        <button className="btn primary" type="submit">
          Create
        </button>
      </form>

      <h2>Users</h2>
      {loading ? (
        <p>Loading…</p>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Email</th>
                <th>Role</th>
                <th>Active</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {users.map((u) => {
                const canToggleActive =
                  u.role !== "superadmin" &&
                  (user?.role === "superadmin" ||
                    (user?.role === "admin" &&
                      u.role === "member" &&
                      u.createdBy === user.id));
                const canChangeRole =
                  user?.role === "superadmin" && (u.role === "admin" || u.role === "member");
                const hasActions = canChangeRole || canToggleActive;
                return (
                  <tr key={u.id}>
                    <td>{u.displayName}</td>
                    <td>{u.email}</td>
                    <td>{u.role}</td>
                    <td>{u.active ? "yes" : "no"}</td>
                    <td className="actions">
                      {canChangeRole ? (
                        <button type="button" className="btn ghost" onClick={() => void onToggleRole(u)}>
                          Make {u.role === "admin" ? "member" : "admin"}
                        </button>
                      ) : null}
                      {canToggleActive && u.active ? (
                        <button
                          type="button"
                          className="btn danger"
                          onClick={() => void onDeactivate(u.id)}
                        >
                          Deactivate
                        </button>
                      ) : null}
                      {canToggleActive && !u.active ? (
                        <button
                          type="button"
                          className="btn ghost"
                          onClick={() => void onActivate(u.id)}
                        >
                          Activate
                        </button>
                      ) : null}
                      {!hasActions ? <span className="muted">—</span> : null}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
