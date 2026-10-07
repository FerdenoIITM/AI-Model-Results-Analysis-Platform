import { Link, NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../features/auth/AuthContext";
import { ChatPage } from "../pages/chat/ChatPage";

export function AppShell() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const canManageUsers = user?.role === "superadmin" || user?.role === "admin";

  return (
    <div className="shell">
      <header className="topbar">
        <div className="brand">
          <Link to="/">AMRAP</Link>
        </div>
        <nav className="nav">
          <NavLink to="/" end className="nav-link">
            Dashboard
          </NavLink>
          <NavLink to="/results" className="nav-link">
            Results
          </NavLink>
          <NavLink to="/chat" className="nav-link">
            Chat
          </NavLink>
          {canManageUsers ? (
            <NavLink to="/users" className="nav-link">
              User Management
            </NavLink>
          ) : null}
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
        <div hidden={location.pathname !== "/chat"}>
          <ChatPage />
        </div>
        <Outlet />
      </main>
    </div>
  );
}
