import { MarkdownText } from "@/components/ui/MathText";

/**
 * A lightweight block renderer for the CoLab preview — headings, lists,
 * blockquotes, rules and fenced code, with every inline run still going
 * through MarkdownText for **bold** + $math$. Not a full CommonMark parser;
 * it covers what researchers actually type in a draft.
 */
export function MarkdownDoc({ source }: { source: string }) {
  const lines = source.replace(/\r\n/g, "\n").split("\n");
  const blocks: React.ReactNode[] = [];
  let para: string[] = [];
  let list: string[] = [];
  let code: string[] | null = null;

  const flushPara = () => {
    if (!para.length) return;
    blocks.push(
      <p key={`p${blocks.length}`} className="my-3 leading-[1.75] text-text-primary">
        <MarkdownText text={para.join(" ")} />
      </p>,
    );
    para = [];
  };
  const flushList = () => {
    if (!list.length) return;
    blocks.push(
      <ul key={`u${blocks.length}`} className="my-3 ml-5 list-disc space-y-1.5 leading-[1.7] text-text-primary">
        {list.map((li, i) => (
          <li key={i}>
            <MarkdownText text={li} />
          </li>
        ))}
      </ul>,
    );
    list = [];
  };

  for (const raw of lines) {
    const line = raw.trimEnd();

    if (code !== null) {
      if (line.trim().startsWith("```")) {
        blocks.push(
          <pre
            key={`c${blocks.length}`}
            className="my-4 overflow-x-auto rounded-md bg-surface-subtle p-3.5 font-mono text-body-s leading-relaxed text-text-primary"
          >
            {code.join("\n")}
          </pre>,
        );
        code = null;
      } else {
        code.push(raw);
      }
      continue;
    }
    if (line.trim().startsWith("```")) {
      flushPara();
      flushList();
      code = [];
      continue;
    }

    if (!line.trim()) {
      flushPara();
      flushList();
      continue;
    }

    const h = /^(#{1,4})\s+(.*)$/.exec(line);
    if (h) {
      flushPara();
      flushList();
      const level = (h[1] ?? "#").length;
      const sizes = ["text-[20px]", "text-[17px]", "text-[15px]", "text-body-s"];
      const size = sizes[Math.min(level, sizes.length) - 1];
      blocks.push(
        <p
          key={`h${blocks.length}`}
          className={`mb-2 mt-5 font-display font-semibold text-text-primary ${size}`}
        >
          <MarkdownText text={h[2] ?? ""} />
        </p>,
      );
      continue;
    }

    if (/^(-{3,}|\*{3,}|_{3,})$/.test(line.trim())) {
      flushPara();
      flushList();
      blocks.push(<hr key={`hr${blocks.length}`} className="my-5 border-border" />);
      continue;
    }

    const bq = /^>\s?(.*)$/.exec(line);
    if (bq) {
      flushPara();
      flushList();
      blocks.push(
        <blockquote
          key={`bq${blocks.length}`}
          className="my-3 border-l-2 border-primary/40 pl-3.5 italic leading-[1.7] text-text-secondary"
        >
          <MarkdownText text={bq[1] ?? ""} />
        </blockquote>,
      );
      continue;
    }

    const li = /^[-*]\s+(.*)$/.exec(line);
    if (li) {
      flushPara();
      list.push(li[1] ?? "");
      continue;
    }

    flushList();
    para.push(line);
  }
  flushPara();
  flushList();

  if (blocks.length === 0) {
    return <span className="text-text-muted">Nothing to preview yet.</span>;
  }
  return <div className="font-body text-body-s">{blocks}</div>;
}
