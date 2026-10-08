import { passwordStrength } from "../lib/validation";

const colors = ["bg-zinc-300 dark:bg-zinc-700", "bg-red-500", "bg-amber-500", "bg-lime-500", "bg-emerald-500"];

export function PasswordStrength({ password }: { password: string }) {
  if (!password) return <span>At least 8 characters. A short phrase is easy to remember and hard to guess.</span>;
  const { score, label } = passwordStrength(password);
  return (
    <span className="flex items-center gap-3">
      <span className="flex flex-1 gap-1" aria-hidden="true">
        {[1, 2, 3, 4].map((bar) => (
          <span
            key={bar}
            className={`h-1 flex-1 rounded-full transition-colors duration-200 ${bar <= score ? colors[score] : colors[0]}`}
          />
        ))}
      </span>
      <span aria-live="polite" className="w-28 shrink-0 text-right">
        Strength: {label}
      </span>
    </span>
  );
}
