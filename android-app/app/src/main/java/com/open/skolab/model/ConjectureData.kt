package com.open.skolab.model

object ConjectureData {
    val conjectures = listOf(
        Conjecture(
            id = "1",
            category = "Quantum Computing",
            title = "The Twisted Superlattice Invariant",
            hypothesis = "Consider a 2D topological insulator under twist angle \$\\theta\$. The Chern number \$C\$ is claimed to be non-zero and calculated using: \$\$C = \\frac{1}{2\\pi} \\int_{BZ} \\Omega(k) d^2k\$\$ where \$\\Omega(k) = \\nabla_k \\times \\mathbf{A}(k)\$ is the Berry curvature. The researcher assumes a single-valued, globally smooth gauge field \$\\mathbf{A}(k)\$ defined over the entire Brillouin Zone torus (BZ). Find the mathematical fallacy.",
            options = listOf(
                "Chern numbers can only be defined for open manifolds.",
                "A globally smooth gauge field on a closed manifold implies Chern number must be zero.",
                "Berry curvature integration requires non-orthogonal coordinates.",
                "The Brillouin Zone torus must have boundary genus \$g > 1\$."
            ),
            correctOptionIndex = 1,
            explanation = "By Stokes' Theorem, integrating the curl of a globally smooth, single-valued 1-form gauge field \$\\mathbf{A}(k)\$ over a closed manifold without boundary (the 2D torus BZ) must yield exactly zero. A non-zero Chern number topologically requires at least two overlapping gauge patches with non-trivial transition functions."
        ),
        Conjecture(
            id = "2",
            category = "Artificial Intelligence",
            title = "Attention Entropy Collapse",
            hypothesis = "A researcher trains a standard Decoder-only Transformer. They notice that the entropy of the attention distribution \$H(A_i) = -\\sum_j A_{ij} \\log A_{ij}\$ collapses to zero at deep layers (\$L > 32\$) for long sequences. They attempt to resolve this by adding a scaling factor: \$\$A_{ij} = \\text{softmax}\\left(\\frac{Q_i K_j^T}{\\sqrt{d_k}} \\cdot \\log(\\text{seq\\_len})\\right)_j\$\$ Explain why this scaling factor actually exacerbates attention entropy collapse instead of fixing it.",
            options = listOf(
                "It increases the query-key dot products, making softmax sharper.",
                "It scales down query-key variance, leading to a uniform distribution.",
                "Attention weight sum becomes larger than 1.",
                "Logarithm is undefined for sequence lengths less than 2."
            ),
            correctOptionIndex = 0,
            explanation = "Multiplying by \$\\log(\\text{seq\\_len})\$ increases the magnitude of the logits before the softmax operation. Higher logit scale amplifies the differences between query-key dot products, driving the softmax probabilities closer to one-hot vectors, which minimizes attention entropy and accelerates collapse."
        ),
        Conjecture(
            id = "3",
            category = "Genomics",
            title = "CRISPR Off-Target Cleavage",
            hypothesis = "To model Cas9 off-target cleavage probability \$P_c\$, a researcher proposes the thermodynamic partition model: \$\$P_c = \\frac{e^{-\\Delta G_{off}/RT}}{e^{-\\Delta G_{on}/RT} + e^{-\\Delta G_{off}/RT}}\$\$ where \$\\Delta G_{off}\$ is the hybridization energy of the mismatched gRNA-DNA loop. If a mismatch occurs in the 'seed region' (first 8-10bp adjacent to PAM), \$\\Delta G_{off}\$ increases by \$4.5 \\text{ kcal/mol}\$. What biological factor is omitted in this equilibrium thermodynamic formulation of Cas9 cleavage?",
            options = listOf(
                "Hybridization enthalpy is always negative.",
                "Cas9 conformational activation is a kinetically controlled non-equilibrium gate.",
                "DNA mismatch repair occurs faster than Cas9 binding.",
                "Thermodynamic partition functions do not apply to cellular volumes."
            ),
            correctOptionIndex = 1,
            explanation = "Cas9 cleavage is not purely determined by binding equilibrium. Seed region mismatches dynamically lock Cas9 in a non-cleaving conformational state. This kinetic activation barrier prevents cleavage even if target binding transiently occurs, making equilibrium thermodynamics insufficient to predict off-target rates."
        )
    )
}
