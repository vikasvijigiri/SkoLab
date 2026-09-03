"""Module-level helpers and per-feature PG cache singletons for the pipeline.

Kept importable from `app.services.platform.pipeline_services` (which re-exports
these names) so existing import paths still resolve after the split.
"""

from app.db.pg_cache import PgBackedCache


def is_field_semantically_relevant(
    collab_field: str, collab_path: str, discipline: str
) -> bool:
    if not discipline:
        return True

    disc_lower = discipline.lower().strip()
    collab_field_lower = (collab_field or "").lower().strip()
    collab_path_lower = (collab_path or "").lower().strip()

    # Direct substring matches
    if disc_lower in collab_field_lower or collab_field_lower in disc_lower:
        return True
    if disc_lower in collab_path_lower:
        return True

    # Split the discipline and collaborator field into words
    disc_words = [
        w.strip()
        for w in disc_lower.replace("and", "").replace("&", "").split()
        if len(w.strip()) > 2
    ]
    collab_words = [w.strip() for w in collab_field_lower.split() if len(w.strip()) > 2]

    # Check if there is any word overlap
    for dw in disc_words:
        for cw in collab_words:
            if dw in cw or cw in dw:
                return True

    # Term expansion for major fields of study
    # Stems mapped to their broader scientific domain keywords
    domain_keywords = {
        "phys": [
            "phys",
            "quantum",
            "spin",
            "antiferromagnet",
            "squaric",
            "condensed",
            "superconduct",
            "particle",
            "magnetic",
            "optical",
            "fluid",
            "thermodynamic",
            "mechanics",
            "gravity",
            "energy",
            "matter",
            "cosmology",
            "phonon",
            "semiconductor",
            "crystallography",
            "spectroscopy",
            "resonance",
            "laser",
            "field",
            "relativity",
            "plasma",
            "astro",
            "nuclear",
        ],
        "comput": [
            "comput",
            "learn",
            "intel",
            "neural",
            "vision",
            "algorithm",
            "software",
            "network",
            "image",
            "data",
            "robot",
            "nlp",
            "processing",
            "code",
            "programming",
            "cyber",
            "security",
            "database",
            "graphics",
            "web",
        ],
        "cs": [
            "comput",
            "learn",
            "intel",
            "neural",
            "vision",
            "algorithm",
            "software",
            "network",
            "image",
            "data",
            "robot",
            "nlp",
            "processing",
            "code",
            "programming",
            "cyber",
            "security",
            "database",
            "graphics",
            "web",
        ],
        "ai": [
            "comput",
            "learn",
            "intel",
            "neural",
            "vision",
            "algorithm",
            "software",
            "network",
            "image",
            "data",
            "robot",
            "nlp",
            "processing",
            "code",
            "programming",
            "cyber",
            "security",
            "database",
            "graphics",
            "web",
        ],
        "bio": [
            "chem",
            "bio",
            "molec",
            "gene",
            "crispr",
            "dna",
            "rna",
            "enzyme",
            "protein",
            "cell",
            "genom",
            "nuclease",
            "chromatin",
            "nucleic",
            "medical",
            "clinical",
            "health",
            "disease",
            "drug",
            "pharma",
            "biotech",
            "immunology",
            "microbiology",
        ],
        "chem": [
            "chem",
            "molec",
            "organ",
            "inorgan",
            "spectroscop",
            "synthes",
            "reaction",
            "cataly",
            "polymer",
            "materials",
            "electro",
            "nano",
        ],
        "math": [
            "math",
            "algebra",
            "calculus",
            "geometry",
            "topology",
            "statistics",
            "probability",
            "discrete",
            "theorem",
            "equation",
            "numerical",
            "optimiz",
        ],
        "eng": [
            "eng",
            "mechanic",
            "electric",
            "civil",
            "chemical",
            "aerospace",
            "material",
            "device",
            "circuit",
            "system",
            "nano",
            "sensor",
            "failure",
        ],
    }

    # Determine the domains of the user's discipline
    matched_domains = []
    for stem, keywords in domain_keywords.items():
        if any(stem in dw for dw in disc_words):
            matched_domains.extend(keywords)

    # Check if the collaborator field contains any of these matched domain keywords
    if matched_domains:
        for kw in matched_domains:
            if kw in collab_field_lower or any(
                kw in cw or cw in kw for cw in collab_words
            ):
                return True

    return False


def is_prestigious_journal(journal: str) -> bool:
    if not journal:
        return False
    j_lower = journal.lower().strip()

    prestigious_patterns = [
        "nature",
        "science",
        "physical review",
        "proceedings of the national academy of sciences",
        "pnas",
        "ieee transactions",
        "ieee/cvf",
        "acm transactions",
        "journal of machine learning research",
        "jmlr",
        "neurips",
        "neural information processing systems",
        "icml",
        "international conference on machine learning",
        "cvpr",
        "iccv",
        "eccv",
        "kdd",
        "sigkdd",
        "association for computational linguistics",
        "emnlp",
        "naacl",
        "aaai",
        "ijcai",
        "cell",
        "lancet",
        "new england journal of medicine",
        "nejm",
        "journal of the american chemical society",
        "jacs",
        "angewandte chemie",
        "advanced materials",
        "monthly notices of the royal astronomical society",
        "mnras",
        "astrophysical journal",
        "journal of high energy physics",
        "jhep",
        "bioinformatics",
    ]

    for pat in prestigious_patterns:
        if pat == "science":
            if (
                j_lower == "science"
                or j_lower.startswith("science ")
                or j_lower.endswith(" science")
            ):
                exclude_words = [
                    "computer",
                    "social",
                    "materials",
                    "political",
                    "applied",
                    "environmental",
                    "management",
                    "education",
                    "policy",
                    "society",
                    "information",
                    "forestry",
                    "agricultural",
                    "clinical",
                    "engineering",
                    "humanities",
                    "sports",
                ]
                if any(w in j_lower for w in exclude_words):
                    continue
                return True
        elif pat == "nature":
            if j_lower == "nature" or j_lower.startswith("nature "):
                return True
        elif pat in j_lower:
            return True

    return False


def extract_metadata_from_abstract(title: str, abstract: str) -> dict:
    title_lower = title.lower()
    abstract_lower = abstract.lower()

    # 1. Methodology
    methodology = "Empirical Analysis & Literature Evaluation"
    if (
        "neural" in title_lower
        or "transformer" in title_lower
        or "deep learning" in title_lower
        or "attention" in title_lower
    ):
        methodology = "Deep Learning & Attention Matrix Optimization"
    elif (
        "quantum" in title_lower
        or "qubit" in title_lower
        or "superconducting" in title_lower
    ):
        methodology = "Quantum Circuit Tomography & Coherence Analysis"
    elif (
        "genome" in title_lower
        or "sequence" in title_lower
        or "dna" in title_lower
        or "regulatory" in title_lower
    ):
        methodology = "Genomic Motif Mapping & Sequence Alignment"
    elif (
        "gravitational" in title_lower
        or "cosmology" in title_lower
        or "astroph" in title_lower
    ):
        methodology = "Numerical Relativity Boundary Solver"
    elif (
        "network" in title_lower
        or "collaboration" in title_lower
        or "workspace" in title_lower
    ):
        methodology = "Collaboration Graph Network Analytics"
    elif (
        "cognitive" in title_lower
        or "eye-tracking" in title_lower
        or "behavioral" in title_lower
    ):
        methodology = "Real-time Cognitive Load EEG Measurement"

    # Try abstract hints
    elif "methodology" in abstract_lower or "method" in abstract_lower:
        # Find sentence containing "method"
        sentences = abstract.split(".")
        for s in sentences:
            if "method" in s.lower() or "approach" in s.lower():
                cleaned = s.strip()
                if len(cleaned) < 80:
                    methodology = cleaned
                    break

    # 2. Tools Used
    tools = []
    # Machine Learning / CS
    if "pytorch" in abstract_lower or "pytorch" in title_lower:
        tools.append("PyTorch")
    if "tensorflow" in abstract_lower:
        tools.append("TensorFlow")
    if "cuda" in abstract_lower:
        tools.append("CUDA C++")
    if "jax" in abstract_lower:
        tools.append("JAX")
    if "gpu" in abstract_lower or "h100" in abstract_lower:
        tools.append("GPU Cluster")
    # Quantum / Physics
    if "qiskit" in abstract_lower:
        tools.append("Qiskit Metal")
    if "hfss" in abstract_lower:
        tools.append("ANSYS HFSS")
    if "cryo" in abstract_lower or "dilution" in abstract_lower:
        tools.append("Cryogenic Fridge")
    # Genomics / Bio
    if "blast" in abstract_lower:
        tools.append("NCBI BLAST")
    if "bioconductor" in abstract_lower or "r/" in abstract_lower:
        tools.append("R/Bioconductor")
    if "nextflow" in abstract_lower:
        tools.append("Nextflow")
    # General / Fallback
    if not tools:
        # Pick 2-3 standard tools based on field
        if "quantum" in title_lower or "phys" in title_lower:
            tools = ["Mathematica", "Python (SciPy)", "HPC Cluster"]
        elif (
            "learn" in title_lower
            or "network" in title_lower
            or "ai" in title_lower
            or "model" in title_lower
        ):
            tools = ["PyTorch", "Hugging Face", "Weights & Biases"]
        elif (
            "genom" in title_lower or "bio" in title_lower or "sequence" in title_lower
        ):
            tools = ["RStudio", "MEME Suite", "BLAST"]
        else:
            tools = ["Python (NumPy)", "MATLAB", "LaTeX"]
    else:
        # pad if too few
        if len(tools) == 1:
            tools.append("Python")
            tools.append("LaTeX")

    # 3. Key Findings
    key_findings = "Demonstrated a robust model performance improvement and identified critical parameter bounds."
    if "quantum" in title_lower:
        key_findings = "Enhanced quantum coherence times and reduced state dephasing errors under environmental noise."
    elif "attention" in title_lower or "transformer" in title_lower:
        key_findings = "Reduced computational complexity and memory usage while preserving tasks downstream perplexity."
    elif "genom" in title_lower:
        key_findings = "Discovered conserved regulatory sequence motifs that control transcription in target organisms."
    elif "gravitational" in title_lower:
        key_findings = "Decreased boundary-reflection artifacts in wave propagation simulations by over 90%."
    elif "collaboration" in title_lower or "workspace" in title_lower:
        key_findings = "Verified that integrated co-author workspaces increase cross-disciplinary productivity metrics."
    elif "cognitive" in title_lower or "behavioral" in title_lower:
        key_findings = "Identified user interface feedback loops that significantly reduce subjective cognitive load."

    return {
        "abstract": abstract,
        "methodology": methodology,
        "tools_used": tools,
        "key_findings": key_findings,
    }


# Per-feature PG caches with appropriate TTLs
# These are the local fast layer; Firestore backs the large enriched docs.
_pg_daily_feed_cache = PgBackedCache(ttl_seconds=3600, name="pipeline_daily_feed")
_pg_match_grants_cache = PgBackedCache(ttl_seconds=3600, name="pipeline_match_grants")
_pg_synergy_cache = PgBackedCache(ttl_seconds=7200, name="pipeline_synergy")
_pg_heatmap_cache = PgBackedCache(ttl_seconds=3600, name="pipeline_heatmap")
_pg_journal_advisor_cache = PgBackedCache(
    ttl_seconds=7200, name="pipeline_journal_advisor"
)
_pg_network_collab_cache = PgBackedCache(
    ttl_seconds=3600, name="pipeline_network_collab"
)
# Effectively-permanent (10y TTL) — a dismissal shouldn't quietly expire and
# bring a paper the user already rejected back into their feed.
_pg_dismissed_recs_cache = PgBackedCache(
    ttl_seconds=315360000, name="pipeline_dismissed_recs"
)
