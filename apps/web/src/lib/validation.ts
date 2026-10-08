export const PASSWORD_MIN = 8;
const PASSWORD_MAX = 128;
const NAME_MAX = 255;

// Deliberately permissive: the provider is the authority on deliverability.
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function emailError(value: string): string | null {
  const email = value.trim();
  if (!email) return "Enter your email address.";
  if (!EMAIL.test(email)) return "Enter a valid email address, like name@example.com.";
  return null;
}

export function passwordError(value: string, mode: "existing" | "new"): string | null {
  if (!value) return mode === "new" ? "Create a password." : "Enter your password.";
  if (mode === "existing") return null;
  if (value.length < PASSWORD_MIN) return `Use at least ${PASSWORD_MIN} characters.`;
  if (value.length > PASSWORD_MAX) return `Use at most ${PASSWORD_MAX} characters.`;
  if (passwordStrength(value).score < 2) return "Make it harder to guess: mix letters, numbers or symbols.";
  return null;
}

export function nameError(value: string): string | null {
  const name = value.trim();
  if (!name) return "Enter your name.";
  if (Array.from(name).length > NAME_MAX) return `Use at most ${NAME_MAX} characters.`;
  // Mirrors the API's Text255 rule (no control characters).
  // eslint-disable-next-line no-control-regex
  if (/[\u0000-\u001f\u007f-\u009f]/.test(name)) return "Use letters, spaces and punctuation only.";
  return null;
}

export interface Strength {
  score: 0 | 1 | 2 | 3 | 4;
  label: "Too short" | "Weak" | "Fair" | "Good" | "Strong";
}

const COMMON = new Set(["password", "12345678", "123456789", "qwertyui", "iloveyou", "11111111", "password1", "abcdefgh"]);

/** A quick, dependency-free estimate. The provider enforces its own policy too. */
export function passwordStrength(value: string): Strength {
  if (value.length < PASSWORD_MIN) return { score: 0, label: "Too short" };
  if (COMMON.has(value.toLowerCase()) || /^(.)\1+$/.test(value)) return { score: 1, label: "Weak" };
  const classes = [/[a-z]/, /[A-Z]/, /\d/, /[^A-Za-z0-9]/].filter((re) => re.test(value)).length;
  let score = classes - 1 + (value.length >= 12 ? 1 : 0) + (value.length >= 16 ? 1 : 0);
  score = Math.max(1, Math.min(4, score));
  const labels = ["Too short", "Weak", "Fair", "Good", "Strong"] as const;
  return { score: score as Strength["score"], label: labels[score] ?? "Weak" };
}

/** A display name for the profile when the provider has none (e.g. "ada" from ada@x.com). */
export function fallbackName(email: string | null): string {
  const local = email?.split("@")[0]?.trim();
  return local || "SkoLab user";
}
