import { useEffect, useLayoutEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { createPortal } from "react-dom";
import { useAuth } from "../../features/auth/AuthContext";
import {
  createUser,
  deleteUser,
  listUsers,
  updateUser,
} from "../../shared/api/client";
import type { UserRole, UserSummary } from "../../shared/api/types";

function formatDateTime(value: string | null | undefined): string {
  if (!value) return "—";
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return "—";
  return d.toLocaleString(undefined, {
    year: "numeric",
    month: "short",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function formatDateShort(value: string | null | undefined): string {
  if (!value) return "—";
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return "—";
  return d.toLocaleDateString(undefined, {
    year: "numeric",
    month: "short",
    day: "2-digit",
  });
}

function DatesCell({ lastLoginAt, createdAt }: { lastLoginAt: string | null; createdAt: string }) {
  const triggerRef = useRef<HTMLSpanElement>(null);
  const tipRef = useRef<HTMLSpanElement>(null);
  const [open, setOpen] = useState(false);
  const [coords, setCoords] = useState<{ top: number; left: number; ready: boolean }>({
    top: 0,
    left: 0,
    ready: false,
  });

  useLayoutEffect(() => {
    if (!open || !triggerRef.current || !tipRef.current) return;
    const rect = triggerRef.current.getBoundingClientRect();
    const tip = tipRef.current.getBoundingClientRect();
    const gap = 8;
    let top = rect.bottom + gap;
    if (top + tip.height > window.innerHeight - 8) {
      top = Math.max(8, rect.top - tip.height - gap);
    }
    let left = rect.left;
    if (left + tip.width > window.innerWidth - 8) {
      left = Math.max(8, window.innerWidth - tip.width - 8);
    }
    setCoords({ top, left, ready: true });
  }, [open, lastLoginAt, createdAt]);

  function show() {
    setCoords((c) => ({ ...c, ready: false }));
    setOpen(true);
  }

  function hide() {
    setOpen(false);
    setCoords({ top: 0, left: 0, ready: false });
  }

  return (
    <>
      <span
        ref={triggerRef}
        className={`dates-hint${open ? " open" : ""}`}
        tabIndex={0}
        onMouseEnter={show}
        onMouseLeave={hide}
        onFocus={show}
        onBlur={hide}
      >
        {formatDateShort(createdAt)}
      </span>
      {open
        ? createPortal(
            <span
              ref={tipRef}
              className="dates-tooltip"
              role="tooltip"
              style={{
                top: coords.top,
                left: coords.left,
                visibility: coords.ready ? "visible" : "hidden",
              }}
            >
              <span>
                <strong>Last login</strong>
                {formatDateTime(lastLoginAt)}
              </span>
              <span>
                <strong>Created</strong>
                {formatDateTime(createdAt)}
              </span>
            </span>,
            document.body
          )
        : null}
    </>
  );
}

function emptyCreateForm(defaultRole: UserRole) {
  return {
    displayName: "",
    email: "",
    password: "",
    role: defaultRole,
  };
}

type EditForm = {
  displayName: string;
  email: string;
  password: string;
  role: UserRole;
  active: boolean;
};

function editFormFromUser(u: UserSummary): EditForm {
  return {
    displayName: u.displayName,
    email: u.email,
    password: "",
    role: u.role,
    active: u.active,
  };
}

export function UsersPage() {
  const { token, user } = useAuth();
  const [users, setUsers] = useState<UserSummary[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [pendingDelete, setPendingDelete] = useState<UserSummary | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [editingUser, setEditingUser] = useState<UserSummary | null>(null);
  const [editForm, setEditForm] = useState<EditForm | null>(null);
  const [saving, setSaving] = useState(false);

  const defaultRole: UserRole = user?.role === "superadmin" ? "admin" : "member";
  const [form, setForm] = useState(() => emptyCreateForm("member"));

  const roleOptions = useMemo(() => {
    if (user?.role === "superadmin") {
      return ["admin", "member"] as UserRole[];
    }
    return ["member"] as UserRole[];
  }, [user]);

  const pendingOwnedCount = useMemo(() => {
    if (!pendingDelete || pendingDelete.role !== "admin") return 0;
    return users.filter((u) => u.createdBy === pendingDelete.id).length;
  }, [pendingDelete, users]);

  function resolveCreatedByName(createdBy: string | null): string {
    if (!createdBy) return "—";
    if (user?.id === createdBy) return user.displayName;
    const creator = users.find((u) => u.id === createdBy);
    return creator?.displayName ?? "—";
  }

  function canManage(target: UserSummary): boolean {
    if (!user) return false;
    if (user.role === "superadmin") return true;
    return (
      user.role === "admin" &&
      target.role === "member" &&
      target.createdBy === user.id
    );
  }

  function canChangeRole(target: UserSummary): boolean {
    return (
      user?.role === "superadmin" &&
      (target.role === "admin" || target.role === "member")
    );
  }

  function canToggleActive(target: UserSummary): boolean {
    if (target.role === "superadmin") return false;
    return canManage(target);
  }

  function canDelete(target: UserSummary): boolean {
    return (
      user?.role === "superadmin" &&
      target.role !== "superadmin" &&
      target.id !== user.id
    );
  }

  function openCreateModal() {
    setError(null);
    setForm(emptyCreateForm(defaultRole === "admin" ? "admin" : "member"));
    setCreateOpen(true);
  }

  function closeCreateModal() {
    if (creating) return;
    setCreateOpen(false);
  }

  function openEditModal(target: UserSummary) {
    setError(null);
    setEditingUser(target);
    setEditForm(editFormFromUser(target));
  }

  function closeEditModal() {
    if (saving) return;
    setEditingUser(null);
    setEditForm(null);
  }

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
    setCreating(true);
    setError(null);
    try {
      await createUser(token, {
        email: form.email,
        password: form.password,
        displayName: form.displayName,
        role: form.role,
      });
      setCreateOpen(false);
      setForm(emptyCreateForm(defaultRole === "admin" ? "admin" : "member"));
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Create failed");
    } finally {
      setCreating(false);
    }
  }

  async function onSaveEdit(e: FormEvent) {
    e.preventDefault();
    if (!token || !editingUser || !editForm) return;
    setSaving(true);
    setError(null);
    try {
      const payload: {
        displayName?: string;
        email?: string;
        password?: string;
        role?: UserRole;
        active?: boolean;
      } = {
        displayName: editForm.displayName,
        email: editForm.email,
      };
      if (editForm.password.trim()) {
        payload.password = editForm.password;
      }
      if (canChangeRole(editingUser) && editForm.role !== editingUser.role) {
        payload.role = editForm.role;
      }
      if (canToggleActive(editingUser)) {
        payload.active = editForm.active;
      }
      await updateUser(token, editingUser.id, payload);
      setEditingUser(null);
      setEditForm(null);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Update failed");
    } finally {
      setSaving(false);
    }
  }

  async function confirmDelete() {
    if (!token || !pendingDelete || user?.role !== "superadmin") return;
    setDeleting(true);
    setError(null);
    try {
      await deleteUser(token, pendingDelete.id);
      setPendingDelete(null);
      setEditingUser(null);
      setEditForm(null);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Delete failed");
    } finally {
      setDeleting(false);
    }
  }

  return (
    <section className="panel">
      <div className="panel-header">
        <h1>User Management</h1>
        <button type="button" className="btn primary" onClick={openCreateModal}>
          Create user
        </button>
      </div>
      {error ? <p className="error">{error}</p> : null}

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
                <th>Created by</th>
                <th>Dates</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {users.map((u) => (
                <tr key={u.id}>
                  <td>{u.displayName}</td>
                  <td>{u.email}</td>
                  <td>{u.role}</td>
                  <td>{u.active ? "yes" : "no"}</td>
                  <td>{resolveCreatedByName(u.createdBy)}</td>
                  <td>
                    <DatesCell lastLoginAt={u.lastLoginAt} createdAt={u.createdAt} />
                  </td>
                  <td className="actions">
                    {canManage(u) ? (
                      <button type="button" className="btn ghost" onClick={() => openEditModal(u)}>
                        Edit
                      </button>
                    ) : (
                      <span className="muted">—</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {createOpen ? (
        <div className="modal-backdrop" role="presentation" onClick={closeCreateModal}>
          <div
            className="modal modal-wide"
            role="dialog"
            aria-modal="true"
            aria-labelledby="create-dialog-title"
            onClick={(e) => e.stopPropagation()}
          >
            <h2 id="create-dialog-title">Create user</h2>
            <form className="modal-form" onSubmit={onCreate}>
              <fieldset className="form-section">
                <legend>Account</legend>
                <div className="grid">
                  <label>
                    Display name
                    <input
                      value={form.displayName}
                      onChange={(e) => setForm((f) => ({ ...f, displayName: e.target.value }))}
                      required
                    />
                  </label>
                  <label>
                    Email
                    <input
                      type="email"
                      value={form.email}
                      onChange={(e) => setForm((f) => ({ ...f, email: e.target.value }))}
                      required
                    />
                  </label>
                  <label>
                    Password
                    <input
                      type="password"
                      value={form.password}
                      onChange={(e) => setForm((f) => ({ ...f, password: e.target.value }))}
                      minLength={8}
                      required
                    />
                  </label>
                  <label>
                    Role
                    <select
                      value={form.role}
                      onChange={(e) => setForm((f) => ({ ...f, role: e.target.value as UserRole }))}
                    >
                      {roleOptions.map((r) => (
                        <option key={r} value={r}>
                          {r}
                        </option>
                      ))}
                    </select>
                  </label>
                </div>
              </fieldset>

              <fieldset className="form-section">
                <legend>Access</legend>
                <p className="muted">
                  Extra access options (modules, data scope, permissions) will be added here later.
                </p>
              </fieldset>

              <div className="modal-actions">
                <button type="button" className="btn ghost" disabled={creating} onClick={closeCreateModal}>
                  Cancel
                </button>
                <button className="btn primary" type="submit" disabled={creating}>
                  {creating ? "Creating…" : "Create"}
                </button>
              </div>
            </form>
          </div>
        </div>
      ) : null}

      {editingUser && editForm ? (
        <div className="modal-backdrop" role="presentation" onClick={closeEditModal}>
          <div
            className="modal modal-wide"
            role="dialog"
            aria-modal="true"
            aria-labelledby="edit-dialog-title"
            onClick={(e) => e.stopPropagation()}
          >
            <h2 id="edit-dialog-title">Edit user</h2>
            <form className="modal-form" onSubmit={onSaveEdit}>
              <fieldset className="form-section">
                <legend>Account</legend>
                <div className="grid">
                  <label>
                    Display name
                    <input
                      value={editForm.displayName}
                      onChange={(e) =>
                        setEditForm((f) => (f ? { ...f, displayName: e.target.value } : f))
                      }
                      required
                    />
                  </label>
                  <label>
                    Email
                    <input
                      type="email"
                      value={editForm.email}
                      onChange={(e) =>
                        setEditForm((f) => (f ? { ...f, email: e.target.value } : f))
                      }
                      required
                    />
                  </label>
                  <label>
                    New password
                    <input
                      type="password"
                      value={editForm.password}
                      onChange={(e) =>
                        setEditForm((f) => (f ? { ...f, password: e.target.value } : f))
                      }
                      minLength={8}
                      placeholder="Leave blank to keep current"
                    />
                  </label>
                  <label>
                    Role
                    {canChangeRole(editingUser) ? (
                      <select
                        value={editForm.role}
                        onChange={(e) =>
                          setEditForm((f) =>
                            f ? { ...f, role: e.target.value as UserRole } : f
                          )
                        }
                      >
                        {roleOptions.map((r) => (
                          <option key={r} value={r}>
                            {r}
                          </option>
                        ))}
                      </select>
                    ) : (
                      <input value={editingUser.role} disabled />
                    )}
                  </label>
                  <div className="toggle-field">
                    <span className="toggle-label">Active</span>
                    {canToggleActive(editingUser) ? (
                      <button
                        type="button"
                        role="switch"
                        aria-checked={editForm.active}
                        className={`toggle ${editForm.active ? "on" : ""}`}
                        onClick={() =>
                          setEditForm((f) => (f ? { ...f, active: !f.active } : f))
                        }
                      >
                        <span className="toggle-thumb" />
                        <span className="toggle-text">{editForm.active ? "Active" : "Inactive"}</span>
                      </button>
                    ) : (
                      <span className="muted">{editingUser.active ? "Active" : "Inactive"}</span>
                    )}
                  </div>
                </div>
              </fieldset>

              <fieldset className="form-section">
                <legend>Access</legend>
                <p className="muted">
                  Extra access options (modules, data scope, permissions) will be added here later.
                </p>
              </fieldset>

              <div className="modal-actions modal-actions-spread">
                {canDelete(editingUser) ? (
                  <button
                    type="button"
                    className="btn danger"
                    disabled={saving}
                    onClick={() => setPendingDelete(editingUser)}
                  >
                    Delete user
                  </button>
                ) : (
                  <span />
                )}
                <div className="modal-actions">
                  <button type="button" className="btn ghost" disabled={saving} onClick={closeEditModal}>
                    Cancel
                  </button>
                  <button className="btn primary" type="submit" disabled={saving}>
                    {saving ? "Saving…" : "Save"}
                  </button>
                </div>
              </div>
            </form>
          </div>
        </div>
      ) : null}

      {pendingDelete ? (
        <div className="modal-backdrop" role="presentation" onClick={() => !deleting && setPendingDelete(null)}>
          <div
            className="modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="delete-dialog-title"
            onClick={(e) => e.stopPropagation()}
          >
            <h2 id="delete-dialog-title">Confirm deletion</h2>
            {pendingDelete.role === "admin" ? (
              pendingOwnedCount > 0 ? (
                <>
                  <p>
                    Admin <strong>{pendingDelete.displayName}</strong> has {pendingOwnedCount}{" "}
                    member{pendingOwnedCount === 1 ? "" : "s"}:
                  </p>
                  <ul className="modal-list">
                    {users
                      .filter((u) => u.createdBy === pendingDelete.id)
                      .map((m) => (
                        <li key={m.id}>
                          {m.displayName} — {m.email}
                        </li>
                      ))}
                  </ul>
                  <p>They will be transferred to Superadmin. Delete this admin?</p>
                </>
              ) : (
                <p>
                  Admin <strong>{pendingDelete.displayName}</strong> has no members. Are you sure
                  you want to delete?
                </p>
              )
            ) : (
              <p>
                Delete <strong>{pendingDelete.displayName}</strong> ({pendingDelete.email})?
              </p>
            )}
            <div className="modal-actions">
              <button
                type="button"
                className="btn ghost"
                disabled={deleting}
                onClick={() => setPendingDelete(null)}
              >
                No, cancel
              </button>
              <button
                type="button"
                className="btn danger"
                disabled={deleting}
                onClick={() => void confirmDelete()}
              >
                {deleting ? "Deleting…" : "Yes, delete"}
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </section>
  );
}
