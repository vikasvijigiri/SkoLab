import { useState, type KeyboardEvent, type ReactNode, type Ref } from "react";
import { TextField, type TextFieldProps } from "./TextField";

interface PasswordFieldProps extends Omit<TextFieldProps, "type" | "trailing"> {
  autoComplete: "current-password" | "new-password";
  ref?: Ref<HTMLInputElement>;
}

function EyeIcon({ open }: { open: boolean }) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" className="size-[18px]" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      {open ? (
        <>
          <path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z" />
          <circle cx="12" cy="12" r="3" />
        </>
      ) : (
        <>
          <path d="M10.6 5.1A9.7 9.7 0 0 1 12 5c6.4 0 10 7 10 7a17.6 17.6 0 0 1-2.6 3.5M6.6 6.6C3.7 8.5 2 12 2 12s3.6 7 10 7a9.7 9.7 0 0 0 5.4-1.6" />
          <path d="m3 3 18 18M9.9 9.9a3 3 0 0 0 4.2 4.2" />
        </>
      )}
    </svg>
  );
}

/** A password input with a show/hide toggle and a Caps Lock warning. */
export function PasswordField({ hint, onKeyDown, onKeyUp, onBlur, ...props }: PasswordFieldProps) {
  const [visible, setVisible] = useState(false);
  const [capsLock, setCapsLock] = useState(false);

  const checkCaps = (event: KeyboardEvent<HTMLInputElement>) => {
    setCapsLock(event.getModifierState("CapsLock"));
  };

  let shownHint: ReactNode = hint;
  if (capsLock) {
    shownHint = (
      <>
        <span className="font-medium text-amber-700 dark:text-amber-400">Caps Lock is on.</span> {hint}
      </>
    );
  }

  return (
    <TextField
      {...props}
      type={visible ? "text" : "password"}
      autoCapitalize="none"
      autoCorrect="off"
      spellCheck={false}
      hint={shownHint}
      onKeyDown={(event) => {
        checkCaps(event);
        onKeyDown?.(event);
      }}
      onKeyUp={(event) => {
        checkCaps(event);
        onKeyUp?.(event);
      }}
      onBlur={(event) => {
        setCapsLock(false);
        onBlur?.(event);
      }}
      trailing={
        <button
          type="button"
          onClick={() => setVisible((v) => !v)}
          aria-label={visible ? "Hide password" : "Show password"}
          aria-pressed={visible}
          className="grid size-9 cursor-pointer place-items-center rounded-md text-zinc-500 transition-colors hover:bg-zinc-100 hover:text-zinc-800 dark:text-zinc-400 dark:hover:bg-zinc-800 dark:hover:text-zinc-100"
        >
          <EyeIcon open={!visible} />
        </button>
      }
    />
  );
}
