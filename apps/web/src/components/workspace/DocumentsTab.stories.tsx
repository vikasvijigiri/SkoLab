import { useRef, useState } from "react";
import type { Meta, StoryObj } from "@storybook/nextjs-vite";
import { PenpotManuscriptSurface } from "./DocumentsTab";

const meta = {
  title: "CoLab/Manuscript workspace",
  parameters: { layout: "fullscreen", fullBleed: true, boxedShell: true },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

function ManuscriptWorkspaceStory() {
  const [draft, setDraft] = useState(`# Abstract

We investigate emergent behavior in frustrated two-dimensional systems and outline a reproducible route from numerical evidence to a concise physical interpretation.

## Introduction

The central question is how local constraints produce long-range signatures without conventional order.`);
  const [focus, setFocus] = useState(false);
  const [preview, setPreview] = useState(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  return (
    <div className="h-full">
      <PenpotManuscriptSurface
        projectName="Quantum Spin Liquids"
        draft={draft}
        canEdit
        focus={focus}
        preview={preview}
        compileState="compiled"
        words={draft.trim().split(/\s+/).length}
        compileError={null}
        onDraftChange={(event) => setDraft(event.target.value)}
        textareaRef={textareaRef}
        onCompile={() => undefined}
        onTogglePreview={() => setPreview((value) => !value)}
        onToggleFocus={() => setFocus((value) => !value)}
        onOpenTemplates={() => undefined}
        onOpenShare={() => undefined}
      />
    </div>
  );
}

export const Writing: Story = {
  render: () => <ManuscriptWorkspaceStory />,
};
