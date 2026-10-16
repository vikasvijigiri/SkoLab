import type { ReactNode } from "react";

/**
 * Line icons drawn on a 24-pixel grid with a round 2-pixel stroke (the
 * Lucide/Feather style), inline so they need no font or request and take the
 * text colour. Decorative: the control that holds one names itself.
 */
function Icon({ className = "size-5", children, strokeWidth = 2 }: { className?: string | undefined; children: ReactNode; strokeWidth?: number }) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" className={`shrink-0 ${className}`} fill="none" stroke="currentColor" strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round">
      {children}
    </svg>
  );
}

type IconProps = { className?: string | undefined };

export const UploadIcon = ({ className }: IconProps) => (
  <Icon className={className}>
    <path d="M12 15V3M7 8l5-5 5 5M4 15v4a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-4" />
  </Icon>
);

export const ZipIcon = ({ className }: IconProps) => (
  <Icon className={className}>
    <path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z" />
    <path d="M14 3v6h6M10 6h1M10 9h1M10 12h1M10 15h1v3h-1z" />
  </Icon>
);

export const FolderIcon = ({ className }: IconProps) => (
  <Icon className={className}>
    <path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
  </Icon>
);

export const FileTextIcon = ({ className }: IconProps) => (
  <Icon className={className}>
    <path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z" />
    <path d="M14 3v6h6M8 13h8M8 17h5" />
  </Icon>
);

export const TrashIcon = ({ className }: IconProps) => (
  <Icon className={className}>
    <path d="M3 6h18M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2M19 6l-1 14a2 2 0 0 1-2 1H8a2 2 0 0 1-2-1L5 6M10 11v6M14 11v6" />
  </Icon>
);

export const ArrowRightIcon = ({ className }: IconProps) => (
  <Icon className={className}>
    <path d="M5 12h14M13 6l6 6-6 6" />
  </Icon>
);

export const ExternalIcon = ({ className }: IconProps) => (
  <Icon className={className}>
    <path d="M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5" />
  </Icon>
);

export const SunIcon = ({ className }: IconProps) => (
  <Icon className={className}>
    <circle cx="12" cy="12" r="4" />
    <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
  </Icon>
);

export const MoonIcon = ({ className }: IconProps) => (
  <Icon className={className}>
    <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z" />
  </Icon>
);

export const SignOutIcon = ({ className }: IconProps) => (
  <Icon className={className}>
    <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9" />
  </Icon>
);

export const SparkIcon = ({ className }: IconProps) => (
  <Icon className={className}>
    <path d="M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9zM19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8z" />
  </Icon>
);

export const GridIcon = ({ className }: IconProps) => (
  <Icon className={className}>
    <rect x="3" y="3" width="7" height="7" rx="1.5" />
    <rect x="14" y="3" width="7" height="7" rx="1.5" />
    <rect x="3" y="14" width="7" height="7" rx="1.5" />
    <rect x="14" y="14" width="7" height="7" rx="1.5" />
  </Icon>
);

/** Physics: an atom. */
export const AtomIcon = ({ className }: IconProps) => (
  <Icon className={className}>
    <circle cx="12" cy="12" r="1.5" />
    <ellipse cx="12" cy="12" rx="10" ry="4" />
    <ellipse cx="12" cy="12" rx="10" ry="4" transform="rotate(60 12 12)" />
    <ellipse cx="12" cy="12" rx="10" ry="4" transform="rotate(120 12 12)" />
  </Icon>
);

/** Chemistry: a flask. */
export const FlaskIcon = ({ className }: IconProps) => (
  <Icon className={className}>
    <path d="M9 3h6M10 3v6L4.5 18.5A1.7 1.7 0 0 0 6 21h12a1.7 1.7 0 0 0 1.5-2.5L14 9V3M7 15h10" />
  </Icon>
);

/** Mathematics: a sigma. */
export const SigmaIcon = ({ className }: IconProps) => (
  <Icon className={className}>
    <path d="M18 7V4H6l6 8-6 8h12v-3" />
  </Icon>
);

/** Biology: a DNA helix. */
export const DnaIcon = ({ className }: IconProps) => (
  <Icon className={className}>
    <path d="M7 3c0 5 10 7 10 9s-10 4-10 9M17 3c0 5-10 7-10 9s10 4 10 9M8.5 6.5h7M9 17.5h6" />
  </Icon>
);
