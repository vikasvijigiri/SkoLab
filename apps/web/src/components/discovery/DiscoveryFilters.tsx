"use client";

import { useMemo } from "react";
import { RotateCcw } from "lucide-react";
import { Chip } from "@/components/ui/Badge";
import { SegmentedControl } from "@/components/ui/SegmentedControl";
import {
  ACTIVITY_STATES,
  CAREER_STAGES,
  DISCOVERY_SORTS,
  INSTITUTION_TYPE_LABELS,
  MOMENTUM_STATES,
  TOPICAL_FOCUS_STOPS,
} from "@/lib/discovery/config";
import type {
  ActivityState,
  CareerStage,
  DiscoveryFilterState,
  DiscoverySort,
  MomentumState,
} from "@/lib/types";

export interface DiscoveryFacets {
  countries: { code: string; count: number }[];
  instTypes: { type: string; count: number }[];
}

interface Props {
  state: DiscoveryFilterState;
  sort: DiscoverySort;
  facets: DiscoveryFacets;
  onChange: (next: DiscoveryFilterState) => void;
  onSortChange: (next: DiscoverySort) => void;
  onReset: () => void;
}

export const DEFAULT_FILTER_STATE: DiscoveryFilterState = {
  careerStage: [],
  activity: [],
  momentum: [],
  topicalFocusMin: 0,
  hasOrcid: false,
  sharesInstitution: false,
  countries: [],
  instTypes: [],
};

function toggle<T>(arr: T[], v: T): T[] {
  return arr.includes(v) ? arr.filter((x) => x !== v) : [...arr, v];
}

const regionName = (code: string): string => {
  try {
    return new Intl.DisplayNames(["en"], { type: "region" }).of(code.toUpperCase()) ?? code;
  } catch {
    return code;
  }
};

const focusStopLabel = (v: number): string => (v === 0 ? "Any" : `≥${Math.round(v * 100)}%`);

function Group({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-2">
      <span className="eyebrow">{label}</span>
      <div className="flex flex-wrap gap-1.5">{children}</div>
    </div>
  );
}

/** Click-only filter + sort rail for the fit-first researcher grid. Every control
 *  is a button / Chip / SegmentedControl — no `<input>` (memory
 *  `no-typing-click-only-ux`). Country / institution-type options come from
 *  `facets` (the current result set), never a hard-coded list. */
export function DiscoveryFilters({ state, sort, facets, onChange, onSortChange, onReset }: Props) {
  const set = (patch: Partial<DiscoveryFilterState>) => onChange({ ...state, ...patch });

  const dirty = useMemo(
    () =>
      state.careerStage.length > 0 ||
      state.activity.length > 0 ||
      state.momentum.length > 0 ||
      state.topicalFocusMin > 0 ||
      state.hasOrcid ||
      state.sharesInstitution ||
      state.countries.length > 0 ||
      state.instTypes.length > 0 ||
      sort !== "fit",
    [state, sort],
  );

  return (
    <div className="flex flex-col gap-4 rounded-sm border border-border/80 bg-surface/70 p-4 shadow-card">
      <div className="flex items-center justify-between">
        <span className="eyebrow">Sort by</span>
        {dirty && (
          <button
            type="button"
            onClick={onReset}
            className="inline-flex items-center gap-1 font-body text-[11px] font-medium text-text-muted hover:text-text-primary"
          >
            <RotateCcw size={11} /> Reset
          </button>
        )}
      </div>
      <div role="group" aria-label="Sort researchers" className="flex flex-wrap gap-1.5">
        {DISCOVERY_SORTS.map((s) => (
          <Chip
            key={s.key}
            selected={sort === s.key}
            aria-pressed={sort === s.key}
            onClick={() => onSortChange(s.key as DiscoverySort)}
          >
            {s.label}
          </Chip>
        ))}
      </div>

      <Group label="Career stage">
        {CAREER_STAGES.map((c) => (
          <Chip
            key={c.key}
            selected={state.careerStage.includes(c.key)}
            aria-pressed={state.careerStage.includes(c.key)}
            onClick={() => set({ careerStage: toggle<CareerStage>(state.careerStage, c.key) })}
          >
            {c.label}
          </Chip>
        ))}
      </Group>

      <Group label="Activity">
        {ACTIVITY_STATES.map((a) => (
          <Chip
            key={a.key}
            selected={state.activity.includes(a.key)}
            aria-pressed={state.activity.includes(a.key)}
            onClick={() => set({ activity: toggle<ActivityState>(state.activity, a.key) })}
          >
            {a.label}
          </Chip>
        ))}
      </Group>

      <Group label="Momentum">
        {MOMENTUM_STATES.map((m) => (
          <Chip
            key={m.key}
            selected={state.momentum.includes(m.key)}
            aria-pressed={state.momentum.includes(m.key)}
            onClick={() => set({ momentum: toggle<MomentumState>(state.momentum, m.key) })}
          >
            {m.label}
          </Chip>
        ))}
      </Group>

      <div className="flex flex-col gap-2">
        <span className="eyebrow">Works on your topic</span>
        <SegmentedControl
          aria-label="Minimum topical focus"
          layoutId="discovery-focus-pill"
          options={TOPICAL_FOCUS_STOPS.map((v) => ({ value: String(v), label: focusStopLabel(v) }))}
          value={String(state.topicalFocusMin)}
          onChange={(v) => set({ topicalFocusMin: Number(v) })}
        />
      </div>

      <Group label="Identity & reach">
        <Chip
          selected={state.hasOrcid}
          aria-pressed={state.hasOrcid}
          onClick={() => set({ hasOrcid: !state.hasOrcid })}
        >
          ORCID-verified
        </Chip>
        <Chip
          selected={state.sharesInstitution}
          aria-pressed={state.sharesInstitution}
          onClick={() => set({ sharesInstitution: !state.sharesInstitution })}
        >
          Shares your institution
        </Chip>
      </Group>

      {facets.countries.length > 0 && (
        <Group label="Country">
          {facets.countries.map((c) => (
            <Chip
              key={c.code}
              selected={state.countries.includes(c.code)}
              aria-pressed={state.countries.includes(c.code)}
              onClick={() => set({ countries: toggle(state.countries, c.code) })}
              title={regionName(c.code)}
            >
              {c.code} · {c.count}
            </Chip>
          ))}
        </Group>
      )}

      {facets.instTypes.length > 0 && (
        <Group label="Institution type">
          {facets.instTypes.map((t) => (
            <Chip
              key={t.type}
              selected={state.instTypes.includes(t.type)}
              aria-pressed={state.instTypes.includes(t.type)}
              onClick={() => set({ instTypes: toggle(state.instTypes, t.type) })}
            >
              {INSTITUTION_TYPE_LABELS[t.type] ?? t.type} · {t.count}
            </Chip>
          ))}
        </Group>
      )}
    </div>
  );
}
