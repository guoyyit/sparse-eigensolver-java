package com.github.guoyyit.sparseeigensolverjava.snapshot;

import com.github.guoyyit.sparseeigensolverjava.core.EigenPairResult;
import com.github.guoyyit.sparseeigensolverjava.core.IterationRecord;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Serializable periodic snapshot of the iterative solver state, sufficient to
 * resume after a process restart without recomputation or duplicate output.
 *
 * <p>Invariants: {@code basis} has orthonormal columns and is orthogonal to
 * every locked eigenvector; {@code projectedB} is the Rayleigh quotient of the
 * active basis; {@code residual} is orthogonal to the basis with norm
 * {@code beta}. Locked pairs are already-committed results: they are carried
 * into the final output verbatim and never recomputed after a resume.</p>
 */
public class SolverSnapshot implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public static final int FORMAT_VERSION = 1;

    public int version = FORMAT_VERSION;
    /** Next outer iteration index to execute. */
    public int outerIteration;
    /** Number of restarts / fresh starts performed (drives deterministic restarts). */
    public int restartCount;
    /** Active basis, column-major: basis[col][row], dim = basisDim x n. */
    public double[][] basis;
    /** Projected matrix B = Q'AQ for the active basis, basisDim x basisDim. */
    public double[][] projectedB;
    /** Current residual vector (next unnormalized Krylov vector), length n. */
    public double[] residual;
    /** Norm of {@link #residual}. */
    public double beta;
    /** Running estimate of ||A||_2 (spectral radius upper bound so far). */
    public double normEstimate;
    /** Per-iteration diagnostics accumulated so far. */
    public List<IterationRecord> history = new ArrayList<>();
    /** Already-converged (committed) eigenpairs, ascending eigenvalue order. */
    public List<EigenPairResult> locked = new ArrayList<>();

    public int basisDim() {
        return basis == null ? 0 : basis.length;
    }
}
