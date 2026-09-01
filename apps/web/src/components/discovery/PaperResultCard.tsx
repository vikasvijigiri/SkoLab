import Link from "next/link";
import { motion } from "framer-motion";
import { Card } from "@/components/ui/Card";
import { Badge } from "@/components/ui/Badge";
import { MathText } from "@/components/ui/MathText";
import { DURATION_NORMAL, EASE_STANDARD } from "@/lib/motion";
import type { OpenAlexWork } from "@/lib/types";

export function PaperResultCard({ w, index }: { w: OpenAlexWork; index: number }) {
  const shortId = w.id.split("/").pop() ?? w.id;
  return (
    <motion.div
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: DURATION_NORMAL, delay: Math.min(index * 0.05, 0.3), ease: EASE_STANDARD }}
    >
      <Link href={`/paper/${encodeURIComponent(shortId)}`}>
        <Card glow interactive accentColor="var(--accent-cyan)" className="flex h-full flex-col gap-1.5">
          <p className="font-display text-[14.5px] font-semibold leading-snug text-text-primary">
            <MathText text={w.display_name} />
          </p>
          <p className="font-body text-[12.5px] text-text-secondary">
            {w.authorships?.slice(0, 3).map((a) => a.author.display_name).join(", ")}
            {w.publication_year ? ` · ${w.publication_year}` : ""}
            {w.primary_location?.source?.display_name ? ` · ${w.primary_location.source.display_name}` : ""}
          </p>
          {w.cited_by_count !== undefined && (
            <Badge accentColor="var(--accent-cyan)" className="w-fit">
              {w.cited_by_count} citations
            </Badge>
          )}
        </Card>
      </Link>
    </motion.div>
  );
}
