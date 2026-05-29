"""
Fix script for feed.py - field-aware daily_conjecture fallback
"""
import sys

with open('app/api/v1/endpoints/feed.py', 'r', encoding='utf-8') as f:
    content = f.read()

# Identify the chunk to replace by finding specific markers
START_MARKER = '    author_data = None\n    resolved_id = None\n    \n    clean_id = author_id.split("/")[-1] if author_id else None'
END_MARKER = '    except Exception as e:\n        return fallback_conjecture'

start_idx = content.find(START_MARKER)
end_idx = content.find(END_MARKER) + len(END_MARKER)

if start_idx == -1:
    print("ERROR: START_MARKER not found!")
    sys.exit(1)
if end_idx == -1:
    print("ERROR: END_MARKER not found!")
    sys.exit(1)

new_block = '''    author_data = None
    resolved_id = None

    clean_id = author_id.split("/")[-1] if author_id else None

    # Step 1: Resolve author
    try:
        if clean_id:
            author_data = await openalex_service.fetch_author_by_id(clean_id)
            if author_data:
                resolved_id = clean_id
        elif name:
            results = await openalex_service.search_authors(name, per_page=1)
            if results:
                author_data = results[0]
                resolved_id = author_data["id"].split("/")[-1]
    except Exception:
        pass

    # Build a field-aware fallback conjecture based on author\'s research area
    fallback_category = "Physics"
    fallback_title = "The Qubit Coherence Paradox"
    fallback_hypothesis = "A researcher prepares a qubit in superposition and subjects it to continuous measurements. According to the quantum Zeno effect, what happens to the state\'s evolution?"
    fallback_options = [
        "The state rapidly collapses into a mixed state.",
        "The state\'s evolution is effectively frozen in its initial superposition.",
        "The measurement decoheres the state into |0> only.",
        "The state oscillates rapidly between |0> and |1>."
    ]
    fallback_correct = 1
    fallback_explanation = "The quantum Zeno effect: frequent measurement freezes quantum evolution, keeping the state near its initial superposition."

    if author_data:
        author_name_lower = (author_data.get("display_name") or "").lower()
        concepts = author_data.get("x_concepts") or []
        topics = author_data.get("topics") or []
        field_names = [c.get("display_name", "").lower() for c in concepts
                       if c.get("display_name") and c.get("display_name").lower() != author_name_lower]
        field_names += [t.get("display_name", "").lower() for t in topics if t.get("display_name")]
        fld = " ".join(field_names)

        if any(kw in fld for kw in ["machine learn", "artificial intel", "neural", "deep learn", "nlp", "reinforcement"]):
            fallback_category = "Machine Learning"
            fallback_title = "The Gradient Vanishing Dilemma"
            fallback_hypothesis = "A deep network with L=50 sigmoid layers runs backpropagation. What happens to the gradient as it propagates from layer L to layer 1?"
            fallback_options = [
                "Stays near constant due to sigmoid\'s bounded range.",
                "Grows exponentially (exploding gradient).",
                "Shrinks exponentially, approaching zero (vanishing gradient).",
                "Oscillates between positive and negative."
            ]
            fallback_correct = 2
            fallback_explanation = "Sigmoid derivative is at most 0.25. Multiplied 50 times it approaches zero — the vanishing gradient problem, solved by ReLU and ResNets."
        elif any(kw in fld for kw in ["biol", "genet", "medicine", "neuro", "protein", "genom"]):
            fallback_category = "Molecular Biology"
            fallback_title = "The Central Dogma Inversion"
            fallback_hypothesis = "A retrovirus integrates its RNA genome using reverse transcriptase. Which step of the central dogma does this process reverse?"
            fallback_options = [
                "DNA to RNA transcription — RNA is used as template.",
                "RNA to DNA — reversing normal transcription direction.",
                "Translation — bypassing ribosomes entirely.",
                "None — this is a standard eukaryotic process."
            ]
            fallback_correct = 1
            fallback_explanation = "Retroviruses use reverse transcriptase to copy RNA to DNA, directly reversing the canonical transcription direction of the central dogma."
        elif any(kw in fld for kw in ["relativity", "gravit", "cosmol", "particle", "high energy", "astrophys"]):
            fallback_category = "General Relativity"
            fallback_title = "The Gravitational Time Dilation Puzzle"
            fallback_hypothesis = "Clock A is at sea level; Clock B atop Everest (h=8848 m). After one year, which shows more elapsed time?"
            fallback_options = [
                "Clock A — stronger gravity speeds up time.",
                "Clock B — higher gravitational potential means time flows faster.",
                "Both identical; altitude has no effect.",
                "Clock B runs slower due to faster rotation at altitude."
            ]
            fallback_correct = 1
            fallback_explanation = "General relativity: clocks at higher gravitational potential tick faster. The difference is approximately 22 microseconds per year for Everest altitude."

    fallback_conjecture = ConjectureResponse(
        id="fallback-1",
        category=fallback_category,
        title=fallback_title,
        hypothesis=fallback_hypothesis,
        options=fallback_options,
        correctOptionIndex=fallback_correct,
        explanation=fallback_explanation
    )

    try:
        if not author_data or not resolved_id:
            return fallback_conjecture
        # Fetch recent works of the author
        works = await openalex_service.fetch_author_works(resolved_id, per_page=5)
        if not works:
            return fallback_conjecture
    except Exception:
        return fallback_conjecture'''

new_content = content[:start_idx] + new_block + content[end_idx:]

with open('app/api/v1/endpoints/feed.py', 'w', encoding='utf-8') as f:
    f.write(new_content)

print("SUCCESS: feed.py updated!")
print(f"Original length: {len(content)}, New length: {len(new_content)}")
