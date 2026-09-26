# sparse-eigensolver-java

A locally callable numerical service (Spring Boot, Java 17) that computes the
**algebraically smallest eigenvalues and eigenvectors of large real symmetric
sparse matrices** given in Compressed Sparse Row (CSR) form. The matrix is
never densified: all large-scale work is O(nnz) sparse matrix–vector products.

## Build, test, run

```bash
mvn test          # 42 tests: validation, solver, recovery, concurrency, REST
mvn package
java -jar target/sparse-eigensolver-java-1.0-SNAPSHOT.jar
# service listens on http://localhost:8080
```

## Algorithm assumptions

* **Matrix class.** Real symmetric, stored as CSR **upper triangle only**
  (every stored entry `(i,j)` has `j >= i`; the lower triangle is implied).
  Column indices within each row are sorted ascending, no duplicates.
* **Solver.** Thick-restart Lanczos iteration (restarted Arnoldi with full
  reorthogonalization, mathematically equivalent for symmetric A):
  - subspace dimension `m = min(n, max(2*nev+8, nev+16))`;
  - Rayleigh–Ritz projection onto the Krylov subspace; the small projected
    problem is solved by a deterministic cyclic Jacobi eigensolver;
  - converged pairs are **locked** (committed, deflated) and the basis is
    thick-restarted on the remaining wanted Ritz vectors;
  - invariant-subspace breakdowns trigger a deterministic fresh start
    orthogonal to all locked and active vectors.
* **Memory.** O(nnz) matrix + O(n·m) basis + O(m²) projected matrix.
  A 102,400-dimensional 2D Laplacian solves in ~14 s using < 150 MB heap.
* **Determinism.** The starting vector is drawn from
  `java.util.Random(seed)`; all orthogonalization sweeps run in fixed order;
  identical (matrix, seed, options) ⇒ bit-identical eigenvalues, eigenvectors,
  residuals and iteration counts. Historical results are reproduced by
  re-submitting the same input and seed.

## Eigenvector sign and normalization rule (fixed)

Every returned eigenvector has **unit L2 norm** and its **largest-magnitude
component is positive** (ties resolved by lowest index). This fully fixes the
otherwise arbitrary ±1 sign, so outputs are comparable across runs.

## Error semantics

A pair `(λ, v)` is **converged** iff

```
‖A·v − λ·v‖₂  ≤  tolerance · max(1, ‖A‖₂-estimate)
```

where the norm estimate is the largest Ritz magnitude seen so far (it
converges to ‖A‖₂). Each returned pair carries its **actual** residual
`residualNorm`, computed by an explicit SpMV at lock time — never the cheap
estimate. Note that for clustered eigenvalues the eigen*value* error can
exceed the residual; the residual is the honest, certifiable measure.

**Failures are never wrapped as success.** When the iteration budget is
exhausted or numerical degeneracy is detected, the result has
`status=FAILED`, an empty `eigenpairs` list, a machine-readable `errorCode`,
and the full per-iteration `history` for diagnosis (retrievable via the
task record / logs).

### Error codes

| code | meaning | HTTP |
|---|---|---|
| `DIMENSION_MISMATCH` | rowPtr/values lengths inconsistent with n | 400 |
| `DUPLICATE_INDEX` | repeated (row,col) entry | 400 |
| `NON_FINITE_VALUE` | NaN/Inf in matrix or parameters | 400 |
| `ASYMMETRIC_STRUCTURE` | lower-triangle entry or unsorted columns | 400 |
| `INDEX_OUT_OF_RANGE` | column index outside [0,n) | 400 |
| `INVALID_PARAMETER` | bad nev / maxIterations / interval | 400 |
| `INPUT_TOO_LARGE` | dimension, nnz or memory estimate over limit | 413 |
| `OVERLOADED` | all worker slots busy | 429 |
| `NOT_CONVERGED` | iteration budget exhausted | 422 |
| `NUMERICAL_DEGENERACY` | non-finite recurrence (e.g. overflow) | 422 |
| `CANCELLED` | cancelled by caller | 422 |
| `TIMEOUT` | wall-clock budget exceeded | 422 |
| `SNAPSHOT_CORRUPT` | snapshot unreadable / wrong version | 422 |

## Request parameters

`POST /api/eigen/solve` (synchronous) and `POST /api/eigen/tasks`
(asynchronous, returns `202` + `taskId`) accept:

```json
{
  "matrix": {"n": 3, "rowPtr": [0,2,4,5], "colIndices": [0,1,1,2,2],
             "values": [2,-1,2,-1,2]},
  "numEigenvalues": 2,
  "tolerance": 1e-8,
  "maxIterations": 300,
  "intervalMin": null,
  "intervalMax": 2.0,
  "seed": 42,
  "snapshotEvery": 25
}
```

| field | default | meaning |
|---|---|---|
| `numEigenvalues` | 1 | number of smallest eigenpairs, 1..n |
| `tolerance` | 1e-8 | relative residual tolerance (see Error semantics) |
| `maxIterations` | 300 | outer (restart) iteration budget |
| `intervalMin/Max` | none | inclusive output filter on eigenvalues |
| `seed` | 42 | deterministic starting-vector seed |
| `snapshotEvery` | 25 | snapshot cadence in outer iterations; 0 disables |

The interval filter selects from the computed smallest eigenpairs; the solver
always works upward from the bottom of the spectrum.

`GET /api/eigen/{taskId}` polls status (`PENDING/RUNNING/CONVERGED/FAILED/
CANCELLED/TIMEOUT`); terminal responses embed the full result.
`DELETE /api/eigen/{taskId}` cancels cooperatively: the worker notices at the
next inner step, publishes **no** partial result, and its slot is reclaimed.

## Concurrency and resource limits

Configured via `application.properties` (prefix `solver.`):

| property | default | meaning |
|---|---|---|
| `max-dimension` | 200000 | matrix dimension ceiling |
| `max-nnz` | 5000000 | stored-nonzero ceiling |
| `max-concurrent-tasks` | 4 | worker slots; excess submissions get `OVERLOADED` |
| `max-wall-millis` | 60000 | per-task wall-clock budget (`TIMEOUT` beyond) |
| `max-memory-bytes` | 512 MiB | per-task estimated-footprint budget |
| `snapshot-dir` | data/snapshots | snapshot root |
| `audit-file` | data/audit.log | audit log path |

The memory check uses a deterministic estimate
(`12·nnz + 4n + 8n·(m+nev+8) + 8m²` bytes) so admission decisions are
reproducible. Every admission, rejection (with reason) and termination (with
wall time and estimate) is appended to the audit log, so anomalous inputs
cannot silently drag down the service.

## Snapshots and restart recovery

Every `snapshotEvery` outer iterations the solver atomically persists its
full state (basis, projected matrix, residual, locked pairs, history) under
`snapshot-dir/<taskId>/`, alongside the original request. If the process
dies, the next startup (`ApplicationRunner`) finds tasks with a request but
no tombstone and **resumes them from the latest snapshot** — locked
(committed) pairs are carried over verbatim, never recomputed or re-emitted,
and the final output is bit-identical to an uninterrupted run (verified by
`RecoveryTest`). Terminal tasks leave a small tombstone and their snapshots
are reclaimed.

## Layout

```
core/       CsrMatrix, MatrixValidator, LanczosSolver, DenseSymmetricEigen,
            options/results, ErrorCode
snapshot/   SolverSnapshot, SnapshotStore (atomic, versioned, tombstones)
service/    EigenTaskManager (admission, limits, cancel, recovery),
            AuditLogger
api/        REST controller, DTOs, exception mapping
config/     SolverProperties
tests/      42 tests: illegal input, non-convergence, concurrent cancel,
            restart recovery, numerical degeneracy, determinism, REST
```
