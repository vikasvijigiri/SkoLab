import httpx
import os
import random
from typing import List, Optional
from app.services.llm_service import is_llm_working, set_llm_limit_exceeded
from app.prompts import PREDICTION_SYSTEM_PROMPT

class PredictionService:
    def __init__(self):
        from app.services.llm_service import LLMService
        self.llm_service = LLMService()
        self.models = [
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant",
            "openai/gpt-oss-120b",
            "qwen/qwen3-32b"
        ]

    async def predict_next_problem(
        self,
        author_name: str,
        expertise: List[str],
        works: List[dict]
    ) -> str:
        """
        Uses Groq's LLM to predict an extremely meticulous, highly viable, and specific 
        next research paper/problem and required tools, grounded perfectly in the 
        author's current skillset, past work, and abstracts of their publications.
        """
        if not works:
            raise ValueError("Cannot predict next problem: no publications found for this researcher.")
        if not is_llm_working():
            raise Exception("LLM services are currently unavailable or rate-limited.")

        # Format past publications with title, year, abstract, and metrics/summaries
        works_context_parts = []
        for i, w in enumerate(works[:10]):  # Up to 10 works
            title = w.get("title", "Untitled")
            year = w.get("year") or w.get("publication_year", "N/A")
            citations = w.get("citations", 0)
            
            # Build rich/grounded info if summary elements are present
            tldr = w.get("tldr")
            techniques = w.get("techniques")
            tools = w.get("tools_and_software")
            core_concepts = w.get("core_concepts")
            
            part = f"Paper #{i+1}: {title} ({year})\nCitations: {citations}\n"
            if tldr:
                part += f"Summary/TLDR: {tldr}\n"
            else:
                abstract = w.get("abstract", "")
                if len(abstract) > 300:
                    abstract = abstract[:300] + "..."
                part += f"Focus/Abstract: {abstract}\n"
                
            if techniques:
                part += f"Techniques/Methods used: {', '.join(techniques)}\n"
            if tools:
                part += f"Tools/Software used: {', '.join(tools)}\n"
            if core_concepts:
                part += f"Core Concepts: {', '.join(core_concepts)}\n"
                
            works_context_parts.append(part)
            
        works_context = "\n".join(works_context_parts)
        expertise_str = ", ".join(expertise)
        
        user_content = (
            f"Researcher Name: {author_name}\n"
            f"Expertise/Skillset: {expertise_str}\n\n"
            f"Selected Publications (Latest/Top):\n{works_context}"
        )
        
        messages = [
            {
                "role": "system",
                "content": PREDICTION_SYSTEM_PROMPT
            },
            {
                "role": "user",
                "content": user_content
            }
        ]

        response = await self.llm_service.query(
            messages=messages,
            models=self.models,
            temperature=0.3,
            max_tokens=250
        )

        if not response.content:
            raise Exception("LLM prediction failed to generate a response.")

        return response.content.strip()

    def _generate_fallback_prediction(
        self,
        author_name: str,
        expertise: List[str],
        works: List[dict]
    ) -> str:
        import re
        
        # Determine keywords context
        expertise_str = " ".join(expertise).lower()
        titles_str = " ".join([w.get("title", "") for w in works]).lower()
        abstracts_str = " ".join([w.get("abstract", "") for w in works]).lower()
        combined_text = f"{expertise_str} {titles_str} {abstracts_str} {author_name.lower()}"
        
        # Tokenize to avoid substring matching issues (e.g. 'rna' in 'internal')
        words_set = set(re.findall(r'\b[a-z]{2,}\b', combined_text))

        # Clean title keywords for dynamic template filling
        keywords_in_titles = []
        for w in works:
            title = w.get("title", "")
            words = [word.strip(".,;:?!()[]'\"-").lower() for word in title.split()]
            for word in words:
                if len(word) > 4 and word not in [
                    "about", "their", "under", "using", "through", "method", "analysis", 
                    "system", "study", "design", "model", "paper", "research", "novel", 
                    "towards", "based", "effect", "effects", "role", "evaluation", 
                    "impact", "investigation", "development", "comparative"
                ]:
                    keywords_in_titles.append(word)
        
        exp_topic = expertise[0] if expertise else "Advanced Scientific Discovery"
        custom_topic = keywords_in_titles[0].capitalize() if keywords_in_titles else exp_topic

        # Classify domain
        if any(x in words_set for x in ["biology", "gene", "genes", "dna", "rna", "protein", "proteins", "cell", "cells", "cellular", "genetics", "microbiology", "expression", "organism", "pathway", "pathways", "enzyme", "enzymes", "biochemical", "sequencing", "biotechnology"]):
            # Biology / Genetics
            return (
                f"**Next Frontier**: High-Resolution Mapping of Cellular Signaling and Gene Expression in {custom_topic} Pathways\n\n"
                "**Toolkit**: BLAST, PyMOL, CRISPR-Cas9, R (Bioconductor)\n\n"
                f"**Logic**: Builds upon molecular patterns identified in {exp_topic} to isolate and regulate genetic targets responsible for expression."
            )

        elif any(x in words_set for x in ["surgery", "surgical", "clinical", "patient", "patients", "therapy", "therapies", "treatment", "treatments", "cancer", "tumor", "tumors", "disease", "diseases", "cardiology", "oncology", "hospital", "wound", "wounds", "drug", "drugs", "gaze", "pediatric", "neurology", "trial", "trials"]):
            # Medicine / Clinical
            return (
                f"**Next Frontier**: Efficacy and Patient Outcome Optimization of {custom_topic} Interventions in Clinical Cohorts\n\n"
                "**Toolkit**: SPSS, R (survival), REDCap, Clinical Trial Protocols\n\n"
                f"**Logic**: Extends recent observations of {exp_topic} to validate clinical protocols and therapeutic safety in larger patient demographics."
            )

        elif any(x in words_set for x in ["quantum", "qubit", "qubits", "superconducting", "topological", "semiconductor", "semiconductors", "gravity", "holographic", "ads", "cft", "dark", "neutrino", "neutrinos", "particle", "particles", "laser", "lasers", "physics", "astrophysics"]):
            # Physics / Quantum
            toolkit = "Qiskit, Mathematica, Python (SciPy)" if "quantum" in words_set else "Mathematica, Python (SciPy), Monte Carlo Simulations"
            return (
                f"**Next Frontier**: Advanced Phase Dynamics and Coherence Optimization in {custom_topic} Frameworks\n\n"
                f"**Toolkit**: {toolkit}\n\n"
                f"**Logic**: Investigates physical limitations and topological properties of {exp_topic} to design stable quantum/classical states under thermal decoherence."
            )

        elif any(x in words_set for x in ["chemistry", "chemical", "synthesis", "synthesized", "material", "materials", "alloy", "alloys", "nanotechnology", "nanostructured", "polymer", "polymers", "catalyst", "catalysts", "organic", "inorganic", "spectroscopy", "perovskite", "electrochemistry"]):
            # Chemistry / Material Science
            return (
                f"**Next Frontier**: Synthesis and Spectroscopic Characterization of Novel Nanostructured {custom_topic} Interfaces\n\n"
                "**Toolkit**: DFT (Density Functional Theory), Gaussian, VASP, SEM/TEM Spectroscopy\n\n"
                f"**Logic**: Designs and synthesizes advanced materials with optimized structural alignments to improve the thermodynamic properties of {exp_topic} systems."
            )

        elif any(x in words_set for x in ["mathematics", "statistical", "statistics", "theorem", "theorems", "calculus", "algebra", "geometry", "topology", "probability", "asymptotic", "differential", "convergence"]):
            # Mathematics
            return (
                f"**Next Frontier**: Asymptotic Behavior and Existence Proofs for Solutions of Non-Linear {custom_topic} Systems\n\n"
                "**Toolkit**: MATLAB, Mathematica, LaTeX, Maple\n\n"
                f"**Logic**: Formulates a rigorous mathematical boundary-value framework to resolve stability and convergence properties of {exp_topic} models."
            )

        elif any(x in words_set for x in ["robot", "robotic", "robotics", "autonomous", "sensor", "sensors", "automation", "mechanical", "electrical", "fluid", "aerodynamics", "engineering"]):
            # Engineering / Robotics
            return (
                f"**Next Frontier**: Real-time Closed-Loop Feedback Control and Sensor Optimization for Autonomous {custom_topic} Systems\n\n"
                "**Toolkit**: MATLAB/Simulink, ROS (Robot Operating System), C++, SolidWorks\n\n"
                f"**Logic**: Improves navigation and operational precision of robotic {exp_topic} platforms under dynamic environmental disturbances."
            )

        elif any(x in words_set for x in ["psychology", "behavioral", "social", "economics", "cognitive", "humanities", "market", "finance", "political", "policy", "education", "sociology", "literature"]):
            # Social Science / Psychology / Humanities
            return (
                f"**Next Frontier**: Behavioral Feedback Loops and Cognitive Load Assessment in {custom_topic} Decision-Making Contexts\n\n"
                "**Toolkit**: R, SPSS, Qualtrics, NVivo\n\n"
                f"**Logic**: Analyzes qualitative and quantitative behavioral datasets to model cognitive responses associated with {exp_topic} paradigms."
            )

        elif any(x in words_set for x in ["computer", "computing", "artificial", "intelligence", "machine", "learning", "deep", "neural", "algorithm", "algorithms", "network", "networks", "software", "database", "databases", "cloud", "recognition", "nlp", "vision", "mining"]):
            # Computer Science / AI
            return (
                f"**Next Frontier**: Scalable Distributed Optimization and Architecture Design for Large-Scale {custom_topic} Learning Systems\n\n"
                "**Toolkit**: Python, PyTorch, CUDA, HuggingFace Transformers\n\n"
                f"**Logic**: Proposes memory-efficient modeling pipelines to optimize parameter constraints and latency of deep networks applied to {exp_topic} workloads."
            )

        # Default fallback
        return (
            f"**Next Frontier**: Novel Methodological Frameworks and Advanced Computational Modeling of {custom_topic} Systems\n\n"
            "**Toolkit**: Python, R, Data Analysis Libraries\n\n"
            f"**Logic**: A logical continuation of the researcher's previous findings in {exp_topic} utilizing advanced statistical and modeling workflows."
        )
