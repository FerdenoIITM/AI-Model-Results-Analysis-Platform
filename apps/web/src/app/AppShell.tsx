import { Link, Outlet, useLocation, useNavigate } from "react-router-dom";
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
          <Link to="/">Dashboard</Link>
          <Link to="/results">Results</Link>
          <Link to="/chat">Chat</Link>
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
        <div hidden={location.pathname !== "/chat"}>
          <ChatPage />
        </div>
        <Outlet />
      </main>
    </div>
  );
}
