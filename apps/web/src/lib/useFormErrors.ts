import { useCallback, useRef, useState } from "react";

type Errors<K extends string> = Partial<Record<K, string | null>>;

/**
 * Validates on submit (not on every keystroke), then re-validates a field as
 * it is corrected. Focus moves to the first invalid field so keyboard and
 * screen-reader users land on the problem.
 */
export function useFormErrors<K extends string>(order: readonly K[]) {
  const [errors, setErrors] = useState<Errors<K>>({});
  const [submitted, setSubmitted] = useState(false);
  const refs = useRef<Partial<Record<K, HTMLInputElement | null>>>({});

  const register = useCallback(
    (key: K) => (node: HTMLInputElement | null) => {
      refs.current[key] = node;
    },
    [],
  );

  const check = useCallback(
    (next: Errors<K>): boolean => {
      setSubmitted(true);
      setErrors(next);
      const first = order.find((key) => next[key]);
      if (first) refs.current[first]?.focus();
      return !first;
    },
    [order],
  );

  const revalidate = useCallback(
    (key: K, message: string | null) => {
      if (submitted) setErrors((prev) => ({ ...prev, [key]: message }));
    },
    [submitted],
  );

  return { errors, register, check, revalidate };
}
