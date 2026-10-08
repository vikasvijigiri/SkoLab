import { Link } from "react-router";
import { AuthLayout, linkClass } from "../components/AuthLayout";

export function NotFound() {
  return (
    <AuthLayout title="Page not found" subtitle="The link may be broken, or the page may have moved.">
      <Link to="/" className={linkClass}>
        Go to SkoLab
      </Link>
    </AuthLayout>
  );
}
