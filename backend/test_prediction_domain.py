import asyncio
import os
import sys
from dotenv import load_dotenv

# Ensure backend directory is in path
sys.path.append(os.path.dirname(os.path.abspath(__file__)))

from app.services.prediction_service import PredictionService

load_dotenv()

async def run_tests():
    print("=== Testing Prediction Service Toolkits & Domains ===")
    
    # 1. Initialize prediction service
    service = PredictionService()
    
    # Mock data for different domains
    profiles = [
        {
            "name": "Dr. Sarah Lin (Biology)",
            "expertise": ["Genetics", "Gene expression", "CRISPR", "Cell biology"],
            "works": [
                {"title": "High-throughput CRISPR sequencing of cancer cell lines", "abstract": "We sequenced DNA to analyze cell pathways."},
                {"title": "RNA expression profile in mammalian cells", "abstract": "Studying genetic markers and proteins."}
            ]
        },
        {
            "name": "Dr. Robert Chen (Medicine/Surgery)",
            "expertise": ["Oncology", "Internal medicine", "Clinical trials", "Surgery"],
            "works": [
                {"title": "Phase III trial of chemotherapy outcomes in oncology patients", "abstract": "Measuring patient survival and toxicity."},
                {"title": "Clinical efficacy of surgical ablation techniques", "abstract": "Improved hospital recovery rates."}
            ]
        },
        {
            "name": "Dr. Elena Rostov (Chemistry)",
            "expertise": ["Physical chemistry", "Nanomaterials", "Polymer synthesis", "Spectroscopy"],
            "works": [
                {"title": "Synthesis of porous graphene nanocomposites for supercapacitors", "abstract": "We characterize the chemical properties and molecular structures."},
                {"title": "Infrared spectroscopy of polymer alignment at interfaces", "abstract": "Investigating molecular thermodynamic shifts."}
            ]
        },
        {
            "name": "Dr. Alan Turing (Computer Science/AI)",
            "expertise": ["Machine learning", "Natural language processing", "Neural networks"],
            "works": [
                {"title": "Transformer networks for real-time translation optimization", "abstract": "Deploying deep models on GPU clusters."},
                {"title": "Unsupervised feature learning via autoencoders", "abstract": "Minimizing latent loss."}
            ]
        },
        {
            "name": "Dr. Emmy Noether (Mathematics)",
            "expertise": ["Abstract algebra", "Differential geometry", "Topology"],
            "works": [
                {"title": "On the asymptotic convergence of differential invariants", "abstract": "A mathematical proof of symmetry theorems."},
                {"title": "Algebraic structures in topological spaces", "abstract": "Defining stable invariants."}
            ]
        }
    ]
    
    # Test Fallback Predictions
    print("\n--- TEST A: Domain-Aware Fallback Predictions (Bypassing API Key) ---")
    for profile in profiles:
        print(f"\nProfile: {profile['name']}")
        print(f"Expertise: {profile['expertise']}")
        print(f"Works: {[w['title'] for w in profile['works']]}")
        
        fallback_res = service._generate_fallback_prediction(
            author_name=profile["name"],
            expertise=profile["expertise"],
            works=profile["works"]
        )
        print("Fallback Output:")
        print(fallback_res)
        print("-" * 50)
        
        # Basic validation checks
        if "Biology" in profile["name"]:
            assert "BLAST" in fallback_res or "CRISPR" in fallback_res or "PyMOL" in fallback_res or "R (Bioconductor)" in fallback_res
        elif "Medicine" in profile["name"]:
            assert "SPSS" in fallback_res or "REDCap" in fallback_res or "Clinical Trial" in fallback_res
        elif "Chemistry" in profile["name"]:
            assert "DFT" in fallback_res or "Gaussian" in fallback_res or "VASP" in fallback_res
        elif "AI" in profile["name"]:
            assert "PyTorch" in fallback_res or "CUDA" in fallback_res or "HuggingFace" in fallback_res
        elif "Mathematics" in profile["name"]:
            assert "Mathematica" in fallback_res or "MATLAB" in fallback_res or "Maple" in fallback_res
            
    print("\nTest A check: All fallback toolkits aligned successfully!")
    
    # Test LLM Predictions (Groq API, if key is valid)
    if service.api_key:
        print("\n--- TEST B: Live LLM Predictions via Groq ---")
        for profile in profiles[:3]:  # Test first 3 profiles to save tokens
            print(f"\nSending live query for: {profile['name']}")
            try:
                res = await service.predict_next_problem(
                    author_name=profile["name"],
                    expertise=profile["expertise"],
                    works=profile["works"]
                )
                print("Live LLM Output:")
                print(res)
                print("-" * 50)
            except Exception as e:
                print(f"Failed to query live LLM: {e}")
    else:
        print("\nGroq API Key not found, skipping Live LLM test.")

if __name__ == "__main__":
    asyncio.run(run_tests())
