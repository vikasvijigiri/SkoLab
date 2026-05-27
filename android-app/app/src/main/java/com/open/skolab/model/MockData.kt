package com.open.skolab.model

import androidx.compose.ui.graphics.Color

object MockData {
    val papers = listOf(
        Paper(
            id = "1",
            title = "Quantum Supremacy via Scalable Superconducting Circuits",
            authors = listOf("John Martinis", "Sergio Boixo", "M. Neven"),
            journal = "Nature",
            year = 2024,
            domain = "Physics",
            subDomain = "Quantum Computing",
            abstractText = "We demonstrate quantum supremacy using a programmable superconducting processor. Our results show a computational speedup of 10^8 over classical supercomputers.",
            disruptionScore = 0.95f,
            noveltyScore = 0.88f,
            citationVelocity = 150.5f,
            hIndex = 45,
            citationCount = 12500,
            journalImpactFactor = 64.8f,
            aiSummary = "This paper establishes the practical boundary of quantum advantage in complex computational tasks.",
            keyInsight = "Scalability is achieved through a new error-correction manifold.",
            bulletPoints = listOf("53-qubit Sycamore processor used", "Random circuit sampling benchmark", "Validated against Summit supercomputer"),
            methodology = listOf("Superconducting Qubits", "Cross-Entropy Benchmarking"),
            latexFormula = "$$\\\\mathcal{H} = \\\\sum_{i} h_i \\\\sigma_i^z + \\\\sum_{i<j} J_{ij} \\\\sigma_i^x \\\\sigma_j^x$$",
            isRetracted = false,
            doi = "10.1038/s41586-019-1666-5",
            pdfUrl = null
        ),
        Paper(
            id = "2",
            title = "Attention Is All You Need",
            authors = listOf("Ashish Vaswani", "Noam Shazeer", "Niki Parmar"),
            journal = "NeurIPS",
            year = 2017,
            domain = "Computer Science",
            subDomain = "NLP",
            abstractText = "We propose a new simple network architecture, the Transformer, based solely on attention mechanisms, dispensing with recurrence and convolutions entirely.",
            disruptionScore = 0.99f,
            noveltyScore = 0.97f,
            citationVelocity = 500.0f,
            hIndex = 120,
            citationCount = 110000,
            journalImpactFactor = 15.2f,
            aiSummary = "The foundational paper for modern LLMs, replacing RNNs with self-attention.",
            keyInsight = "Self-attention allows for massive parallelization of sequence data.",
            bulletPoints = listOf("Introduced Multi-Head Attention", "Positional encoding replaces recurrence", "Achieved SOTA on WMT 2014"),
            methodology = listOf("Transformer Architecture", "Self-Attention"),
            latexFormula = "$$\\\\text{Attention}(Q, K, V) = \\\\text{softmax}(\\\\frac{QK^T}{\\\\sqrt{d_k}})V$$",
            isRetracted = false,
            doi = "10.48550/arXiv.1706.03762",
            pdfUrl = null
        ),
        Paper(
            id = "3",
            title = "Neural Architecture Search with Reinforcement Learning",
            authors = listOf("Barret Zoph", "Quoc V. Le"),
            journal = "arXiv",
            year = 2023,
            domain = "Computer Science",
            subDomain = "AutoML",
            abstractText = "We use a recurrent neural network to generate the model descriptions of neural networks and train this RNN with reinforcement learning.",
            disruptionScore = 0.72f,
            noveltyScore = 0.91f,
            citationVelocity = 89.2f,
            hIndex = 30,
            citationCount = 4500,
            journalImpactFactor = 0.0f,
            aiSummary = "Automating the design of neural networks using RL to find optimal architectures.",
            keyInsight = "Policy gradient methods can effectively navigate the discrete space of architectures.",
            bulletPoints = listOf("RNN controller generates architectures", "Reinforcement learning optimizes for accuracy", "Outperforms human-designed models"),
            methodology = listOf("Reinforcement Learning", "Policy Gradients"),
            latexFormula = "\$\$J(\\\\theta) = E_{P(\\\\tau;\\\\theta)}[R(\\\\tau)]$$",
            isRetracted = false,
            doi = "10.48550/arXiv.1611.01578",
            pdfUrl = null
        ),
        Paper(
            id = "4",
            title = "CRISPR-Cas9 Gene Editing in Human Embryos",
            authors = listOf("Jennifer Doudna", "Hong Ma", "Shoukhrat Mitalipov"),
            journal = "Nature",
            year = 2024,
            domain = "Biology",
            subDomain = "Genetics",
            abstractText = "We report the successful correction of a heterozygous MYBPC3 mutation in human preimplantation embryos using CRISPR-Cas9.",
            disruptionScore = 0.88f,
            noveltyScore = 0.75f,
            citationVelocity = 120.0f,
            hIndex = 95,
            citationCount = 18000,
            journalImpactFactor = 64.8f,
            aiSummary = "Precise gene editing in early-stage embryos is feasible with high efficiency.",
            keyInsight = "Homology-directed repair is the dominant mechanism in early zygotes.",
            bulletPoints = listOf("Correction of MYBPC3 mutation", "Reduced mosaicism", "High efficiency gene correction"),
            methodology = listOf("CRISPR-Cas9", "HDR"),
            latexFormula = null,
            isRetracted = false,
            doi = "10.1038/nature23305",
            pdfUrl = null
        ),
        Paper(
            id = "5",
            title = "Room-Temperature Superconductivity in Carbonaceous Sulfur Hydride",
            authors = listOf("Ranga Dias", "Maddi Seger", "N. Dasenbrock-Gammon"),
            journal = "Nature",
            year = 2023,
            domain = "Physics",
            subDomain = "Condensed Matter",
            abstractText = "We observe superconductivity in a photochemically transformed carbonaceous sulfur hydride system with a maximum Tc of 288 K at 267 GPa.",
            disruptionScore = 0.98f,
            noveltyScore = 0.94f,
            citationVelocity = 210.3f,
            hIndex = 15,
            citationCount = 800,
            journalImpactFactor = 64.8f,
            aiSummary = "The first claim of superconductivity at room temperature under extreme pressure.",
            keyInsight = "High pressure stabilizes the hydrogen-rich lattice structure.",
            bulletPoints = listOf("Tc of 288 K achieved", "Pressure of 267 GPa required", "Diamond anvil cell methodology"),
            methodology = listOf("Diamond Anvil Cell", "Photochemical Transformation"),
            latexFormula = "\$\$P_c \\\\approx 267 \\\\text{ GPa}$$",
            isRetracted = true,
            doi = "10.1038/s41586-020-2801-z",
            pdfUrl = null
        ),
        Paper(
            id = "6",
            title = "Large Language Models are Few-Shot Learners",
            authors = listOf("Tom Brown", "Benjamin Mann", "Nick Ryder"),
            journal = "arXiv",
            year = 2020,
            domain = "Computer Science",
            subDomain = "AI",
            abstractText = "We train GPT-3, an autoregressive language model with 175 billion parameters, and test its few-shot abilities.",
            disruptionScore = 0.85f,
            noveltyScore = 0.82f,
            citationVelocity = 350.4f,
            hIndex = 80,
            citationCount = 25000,
            journalImpactFactor = 0.0f,
            aiSummary = "GPT-3 demonstrates that massive scale leads to emergent few-shot task performance.",
            keyInsight = "Scale significantly improves zero-shot and few-shot performance.",
            bulletPoints = listOf("175B parameters", "Few-shot learning without fine-tuning", "Broad task generalizability"),
            methodology = listOf("Autoregressive Transformer", "Scale Laws"),
            latexFormula = null,
            isRetracted = false,
            doi = "10.48550/arXiv.2005.14165",
            pdfUrl = null
        ),
        Paper(
            id = "7",
            title = "AlphaFold: A solution to the 50-year-old protein folding problem",
            authors = listOf("John Jumper", "Richard Evans", "Demis Hassabis"),
            journal = "Nature",
            year = 2021,
            domain = "Biology",
            subDomain = "Bioinformatics",
            abstractText = "We present AlphaFold, an AI system that predicts protein structures with atomic accuracy from amino acid sequences.",
            disruptionScore = 0.97f,
            noveltyScore = 0.96f,
            citationVelocity = 410.0f,
            hIndex = 55,
            citationCount = 15000,
            journalImpactFactor = 64.8f,
            aiSummary = "AlphaFold solves one of biology's greatest challenges using deep learning.",
            keyInsight = "End-to-end learning of spatial constraints from sequence data.",
            bulletPoints = listOf("Atomic accuracy in structure prediction", "Large-scale structure database created", "Open-access for researchers"),
            methodology = listOf("Evoformer", "End-to-end Deep Learning"),
            latexFormula = null,
            isRetracted = false,
            doi = "10.1038/s41586-021-03819-2",
            pdfUrl = null
        ),
        Paper(
            id = "8",
            title = "The Bitcoin Whitepaper: A Peer-to-Peer Electronic Cash System",
            authors = listOf("Satoshi Nakamoto"),
            journal = "Cryptography Mailing List",
            year = 2008,
            domain = "Computer Science",
            subDomain = "Cryptography",
            abstractText = "A pure peer-to-peer version of electronic cash would allow online payments to be sent directly from one party to another without going through a financial institution.",
            disruptionScore = 1.0f,
            noveltyScore = 0.98f,
            citationVelocity = 120.0f,
            hIndex = 200,
            citationCount = 35000,
            journalImpactFactor = 0.0f,
            aiSummary = "The foundational paper for blockchain and decentralized finance.",
            keyInsight = "Solving the double-spending problem using a proof-of-work consensus.",
            bulletPoints = listOf("Proof-of-Work consensus", "Distributed ledger technology", "Elimination of central intermediaries"),
            methodology = listOf("Proof-of-Work", "Merkle Trees"),
            latexFormula = null,
            isRetracted = false,
            doi = "bitcoin.pdf",
            pdfUrl = null
        )
    )

    val authors = listOf(
        Author(
            id = "A1",
            name = "Dr. John Martinis",
            institution = "UC Santa Barbara",
            country = "USA",
            orcidId = "0000-0002-1234-5678",
            fingerprintType = "Trailblazer",
            radarScores = mapOf(
                "Disruption" to 0.95f, "Novelty" to 0.88f, "Depth" to 0.92f, 
                "Velocity" to 0.85f, "Influence" to 0.90f, "Breadth" to 0.75f
            ),
            careerArc = listOf(2010 to 0.4f, 2014 to 0.6f, 2019 to 0.95f, 2024 to 0.85f),
            topPapers = papers.filter { it.authors.contains("John Martinis") },
            collaborators = listOf("Sergio Boixo", "Hartmut Neven"),
            totalPapers = 150,
            avgDisruptionScore = 0.65f
        ),
        Author(
            id = "A2",
            name = "Prof. Jennifer Doudna",
            institution = "UC Berkeley",
            country = "USA",
            orcidId = "0000-0001-5678-1234",
            fingerprintType = "Deep Specialist",
            radarScores = mapOf(
                "Disruption" to 0.88f, "Novelty" to 0.75f, "Depth" to 0.98f, 
                "Velocity" to 0.80f, "Influence" to 0.95f, "Breadth" to 0.65f
            ),
            careerArc = listOf(2005 to 0.3f, 2012 to 0.99f, 2018 to 0.85f, 2024 to 0.78f),
            topPapers = papers.filter { it.authors.contains("Jennifer Doudna") },
            collaborators = listOf("Emmanuelle Charpentier", "Feng Zhang"),
            totalPapers = 320,
            avgDisruptionScore = 0.72f
        ),
        Author(
            id = "A3",
            name = "Ashish Vaswani",
            institution = "Essential AI",
            country = "USA",
            orcidId = null,
            fingerprintType = "Trailblazer",
            radarScores = mapOf(
                "Disruption" to 0.99f, "Novelty" to 0.97f, "Depth" to 0.90f, 
                "Velocity" to 0.95f, "Influence" to 0.99f, "Breadth" to 0.82f
            ),
            careerArc = listOf(2015 to 0.2f, 2017 to 0.99f, 2021 to 0.88f, 2023 to 0.92f),
            topPapers = papers.filter { it.authors.contains("Ashish Vaswani") },
            collaborators = listOf("Noam Shazeer", "Niki Parmar"),
            totalPapers = 45,
            avgDisruptionScore = 0.85f
        ),
        Author(
            id = "A4",
            name = "Satoshi Nakamoto",
            institution = "Independent",
            country = "Unknown",
            orcidId = null,
            fingerprintType = "Lone Wolf",
            radarScores = mapOf(
                "Disruption" to 1.0f, "Novelty" to 0.98f, "Depth" to 0.85f, 
                "Velocity" to 0.60f, "Influence" to 1.0f, "Breadth" to 0.50f
            ),
            careerArc = listOf(2008 to 1.0f),
            topPapers = papers.filter { it.authors.contains("Satoshi Nakamoto") },
            collaborators = emptyList(),
            totalPapers = 1,
            avgDisruptionScore = 1.0f
        )
    )

    val fieldSkoLabes = listOf(
        FieldEntropy(
            field = "Quantum Computing",
            subFields = listOf("Error Correction", "Topological Qubits", "Quantum Algorithms"),
            entropyScore = 88.5f,
            weeklyDelta = 3.2f,
            phaseStatus = "PHASE TRANSITION",
            historicalEntropy = listOf(2015 to 40f, 2019 to 75f, 2024 to 88.5f),
            topPapers = papers.filter { it.domain == "Physics" },
            topAuthors = authors.filter { it.name == "Dr. John Martinis" }
        ),
        FieldEntropy(
            field = "Artificial Intelligence",
            subFields = listOf("LLMs", "Reasoning", "Multimodal"),
            entropyScore = 94.2f,
            weeklyDelta = 5.7f,
            phaseStatus = "PHASE TRANSITION",
            historicalEntropy = listOf(2012 to 30f, 2017 to 60f, 2024 to 94.2f),
            topPapers = papers.filter { it.domain == "Computer Science" },
            topAuthors = authors.filter { it.name == "Ashish Vaswani" }
        ),
        FieldEntropy(
            field = "Genetics",
            subFields = listOf("CRISPR", "Synthetic Biology", "Epigenetics"),
            entropyScore = 72.1f,
            weeklyDelta = 1.4f,
            phaseStatus = "ACCELERATING",
            historicalEntropy = listOf(2010 to 20f, 2015 to 50f, 2024 to 72.1f),
            topPapers = papers.filter { it.domain == "Biology" },
            topAuthors = authors.filter { it.name == "Prof. Jennifer Doudna" }
        ),
        FieldEntropy(
            field = "Cryptography",
            subFields = listOf("Blockchain", "ZK-Proofs", "Lattice-based"),
            entropyScore = 65.4f,
            weeklyDelta = -0.5f,
            phaseStatus = "STABLE",
            historicalEntropy = listOf(2008 to 80f, 2015 to 70f, 2024 to 65.4f),
            topPapers = papers.filter { it.subDomain == "Cryptography" },
            topAuthors = authors.filter { it.name == "Satoshi Nakamoto" }
        )
    )

    val collections = listOf(
        Collection(
            id = "C1", name = "Quantum Frontiers", emoji = "⚛️", accentColor = Color(0xFF6C63FF),
            papers = papers.filter { it.domain == "Physics" }, createdAt = 1713888000000, updatedAt = 1713974400000
        ),
        Collection(
            id = "C2", name = "AI Renaissance", emoji = "🧠", accentColor = Color(0xFF00E5C3),
            papers = papers.filter { it.domain == "Computer Science" }, createdAt = 1713801600000, updatedAt = 1713974400000
        ),
        Collection(
            id = "C3", name = "Gene Editing", emoji = "🧬", accentColor = Color(0xFFFFB547),
            papers = papers.filter { it.domain == "Biology" }, createdAt = 1713715200000, updatedAt = 1713974400000
        )
    )

    val alerts = listOf(
        Alert(id = "L1", type = "Field", target = "Quantum Computing", threshold = 85.0f, frequency = "Instant", isActive = true, lastTriggered = 1713974400000),
        Alert(id = "L2", type = "Author", target = "Ashish Vaswani", threshold = null, frequency = "Daily", isActive = true, lastTriggered = null),
        Alert(id = "L3", type = "D-Index Spike", target = "Genetics", threshold = 0.85f, frequency = "Weekly", isActive = false, lastTriggered = null),
        Alert(id = "L4", type = "Rising Star", target = "Deep Learning", threshold = null, frequency = "Daily", isActive = true, lastTriggered = 1713888000000),
        Alert(id = "L5", type = "New Retraction", target = "Physics", threshold = null, frequency = "Instant", isActive = true, lastTriggered = 1713974400000)
    )

    fun generateDynamicMockAuthor(name: String, id: String? = null): com.open.skolab.network.AuthorResponse {
        val cleanId = id ?: "A_dynamic_${Math.abs(name.hashCode())}"
        val institutions = listOf(
            "Stanford University", "Massachusetts Institute of Technology", 
            "UC Berkeley", "Harvard University", "California Institute of Technology", 
            "Princeton University", "Oxford University", "Cambridge University", 
            "ETH Zurich", "Tsinghua University", "UC San Diego", "Columbia University"
        )
        val inst = institutions[Math.abs(name.hashCode()) % institutions.size]
        val hIndex = 15 + (Math.abs(name.hashCode()) % 20)
        val worksCount = 30 + (Math.abs(name.hashCode()) % 80)
        val citations = worksCount * 12 + (Math.abs(name.hashCode()) % 200)
        
        val works = listOf(
            com.open.skolab.network.Work(
                title = "Emergent Behaviors and Scalable Paradigms in ${name}'s Frontiers",
                year = 2024,
                doi = "10.1145/dynamic.${Math.abs(name.hashCode())}",
                journal = "Journal of Advanced Science",
                is_open_access = true,
                citations = hIndex * 2,
                creativity_score = 0.84,
                complexity_score = 0.79,
                impact_factor = 5.4,
                disruption_score = 0.62,
                semantic_novelty = 0.81,
                open_science_score = 0.90,
                authors = listOf(name, "Dr. Sarah Jenkins")
            ),
            com.open.skolab.network.Work(
                title = "Empirical Validation of Phase Boundaries and Entropy in Modern Systems",
                year = 2022,
                doi = "10.1145/dynamic2.${Math.abs(name.hashCode())}",
                journal = "Nature Communications",
                is_open_access = true,
                citations = hIndex,
                creativity_score = 0.78,
                complexity_score = 0.85,
                impact_factor = 12.1,
                disruption_score = 0.45,
                semantic_novelty = 0.74,
                open_science_score = 0.85,
                authors = listOf(name, "Prof. Linus Vance")
            )
        )

        return com.open.skolab.network.AuthorResponse(
            id = cleanId,
            display_name = name,
            orcid = "0000-0002-${Math.abs(name.hashCode()) % 9000 + 1000}-${Math.abs(name.hashCode()) % 9000 + 1000}",
            h_index = hIndex,
            i10_index = maxOf(0, hIndex - 5),
            works_count = worksCount,
            cited_by_count = citations,
            institution = inst,
            field_of_study = "Computer Science",
            expertise = listOf("Quantum Frontiers", "Disruptive Mechanics", "Mathematical Modeling"),
            academic_history = listOf("Academic Portfolio — $inst"),
            works = works,
            average_creativity = 0.81,
            average_complexity = 0.82,
            average_activity = 0.79,
            average_skill_score = 0.88,
            average_impact = 0.80,
            innovation_score = 75.0,
            disruption_score = 0.54,
            citation_acceleration = 8.4,
            future_impact_score = 82.5,
            network_centrality = 0.78,
            semantic_novelty = 0.75,
            interdisciplinary_index = 0.68,
            policy_patent_score = 24.0,
            open_science_score = 0.85,
            collaboration_diversity = 0.58,
            research_consistency = 0.82,
            next_prediction = "**Next Frontier**: High-Resolution Mapping of Computational Modeling in ${name}'s research loops.\n\n**Toolkit**: Python, PyTorch, CUDA\n\n**Logic**: Builds upon recent observations to optimize operational boundaries in their target field.",
            top_experimental_tools = listOf(
                com.open.skolab.network.ToolUsage("Python", 42, "Software"),
                com.open.skolab.network.ToolUsage("PyTorch", 24, "Software")
            ),
            similar_researchers = emptyList()
        )
    }
}

