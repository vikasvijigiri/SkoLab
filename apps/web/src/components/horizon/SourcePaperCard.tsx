import { ExternalLink } from "lucide-react";
import { Card } from "@/components/ui/Card";
import { Badge } from "@/components/ui/Badge";
import { MathText } from "@/components/ui/MathText";
import type { PaperSource } from "@/lib/types";

/** One reference-study card in the Horizon prediction result (pioneering / latest). */
export function SourcePaperCard({ paper, accentColor }: { paper: PaperSource; accentColor: string }) {
  return (
    <Card className="border-border/30 bg-surface/30 flex flex-col gap-1.5">
      <h4 className="font-display text-[13.5px] font-semibold text-text-primary line-clamp-2">
        <MathText text={paper.title} />
      </h4>
      <p className="font-body text-[12px] text-text-secondary">
        {paper.authors.join(", ")} · {paper.year}
      </p>
      <div className="flex items-center justify-between mt-2 pt-1 border-t border-border/10">
        <Badge accentColor={accentColor}>{paper.cited_by_count} Citations</Badge>
        {paper.doi && (
          <a
            href={paper.doi.startsWith("http") ? paper.doi : `https://doi.org/${paper.doi}`}
            target="_blank"
            rel="noopener noreferrer"
            className="flex items-center gap-1 font-body text-[11.5px] text-primary hover:underline"
          >
            Read Study
            <ExternalLink size={10} />
          </a>
        )}
      </div>
    </Card>
  );
}
