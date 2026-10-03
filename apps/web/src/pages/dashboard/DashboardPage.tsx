import { Link } from "react-router-dom";
import { useAuth } from "../../features/auth/AuthContext";

export function DashboardPage() {
  const { user } = useAuth();
  const canManageUsers = user?.role === "superadmin" || user?.role === "admin";

  return (
    <section className="panel">
      <h1>Dashboard</h1>
      <p>
        Signed in as <strong>{user?.displayName}</strong> ({user?.email}) with role{" "}
        <strong>{user?.role}</strong>.
      </p>
      <ul className="dashboard-links">
        <li>
          <Link to="/results">Model Results</Link>
          <span className="muted"> — ingested JSON submissions with filters</span>
        </li>
        <li>
          <Link to="/chat">Ask AMRAP</Link>
          <span className="muted"> — natural-language chat over your results (RAG)</span>
        </li>
        {canManageUsers ? (
          <li>
            <Link to="/users">User Management</Link>
            <span className="muted"> — create, edit, deactivate users</span>
          </li>
        ) : null}
      </ul>
    </section>
  );
}
