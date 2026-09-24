import { Link, Outlet, useNavigate } from "react-router-dom";
import { useAuth } from "../features/auth/AuthContext";

export function AppShell() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const canManageUsers = user?.role === "superadmin" || user?.role === "admin";

  return (
    <div className="shell">
      <header className="topbar">
        <div className="brand">
          <Link to="/">AMRAP</Link>
        </div>
        <nav className="nav">
          <Link to="/">Dashboard</Link>
          {canManageUsers ? <Link to="/users">User Management</Link> : null}
        </nav>
        <div className="user-meta">
          <span>
            {user?.displayName} · <em>{user?.role}</em>
          </span>
          <button
            type="button"
            className="btn ghost"
            onClick={() => {
              logout();
              navigate("/login");
            }}
          >
            Log out
          </button>
        </div>
      </header>
      <main className="content">
        <Outlet />
      </main>
    </div>
  );
}
