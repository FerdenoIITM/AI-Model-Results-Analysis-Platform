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
      {canManageUsers ? (
        <p>
          <Link to="/users">Open User Management</Link>
        </p>
      ) : (
        <p className="muted">Read-only access. User Management is not available for members.</p>
      )}
    </section>
  );
}
