package com.github.guoyyit.sparseeigensolverjava.api;

import com.github.guoyyit.sparseeigensolverjava.api.dto.CsrMatrixDto;
import com.github.guoyyit.sparseeigensolverjava.api.dto.SolveRequestDto;
import com.github.guoyyit.sparseeigensolverjava.api.dto.SolveResponseDto;
import com.github.guoyyit.sparseeigensolverjava.core.CsrMatrix;
import com.github.guoyyit.sparseeigensolverjava.core.EigenResult;
import com.github.guoyyit.sparseeigensolverjava.core.ErrorCode;
import com.github.guoyyit.sparseeigensolverjava.core.MatrixValidationException;
import com.github.guoyyit.sparseeigensolverjava.core.SolveStatus;
import com.github.guoyyit.sparseeigensolverjava.core.SolverOptions;
import com.github.guoyyit.sparseeigensolverjava.service.EigenTaskManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Local REST entry points for submitting, polling and cancelling solves.
 *
 * <ul>
 *   <li>POST /api/eigen/solve   — synchronous solve, returns the final result</li>
 *   <li>POST /api/eigen/tasks   — asynchronous submit, returns 202 + taskId</li>
 *   <li>GET  /api/eigen/{id}    — poll status; includes the result when terminal</li>
 *   <li>DELETE /api/eigen/{id}  — cooperative cancellation</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/eigen")
public class EigenController {

    private final EigenTaskManager taskManager;

    public EigenController(EigenTaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @PostMapping("/solve")
    public ResponseEntity<SolveResponseDto> solve(@RequestBody SolveRequestDto request) {
        CsrMatrix matrix = toMatrix(request.matrix);
        SolverOptions options = toOptions(request);
        EigenResult result = taskManager.solveSync(matrix, options);
        SolveResponseDto body = toResponse(null, result);
        // Failures are explicit: 200 with CONVERGED, otherwise 422 with code.
        return ResponseEntity
                .status(result.status() == SolveStatus.CONVERGED ? HttpStatus.OK
                        : HttpStatus.UNPROCESSABLE_ENTITY)
                .body(body);
    }

    @PostMapping("/tasks")
    public ResponseEntity<Map<String, String>> submit(@RequestBody SolveRequestDto request) {
        CsrMatrix matrix = toMatrix(request.matrix);
        SolverOptions options = toOptions(request);
        String taskId = taskManager.submit(matrix, options);
        return ResponseEntity.accepted().body(Map.of("taskId", taskId, "status", "PENDING"));
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<SolveResponseDto> poll(@PathVariable String taskId) {
        Optional<EigenTaskManager.TaskView> view = taskManager.poll(taskId);
        if (view.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        EigenTaskManager.TaskView v = view.get();
        SolveResponseDto body = new SolveResponseDto();
        body.taskId = v.taskId();
        body.status = v.status().name();
        if (v.result() != null) {
            SolveResponseDto full = toResponse(v.taskId(), v.result());
            full.taskId = v.taskId();
            return ResponseEntity.ok(full);
        }
        return ResponseEntity.ok(body);
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<Void> cancel(@PathVariable String taskId) {
        return taskManager.cancel(taskId)
                ? ResponseEntity.accepted().build()
                : ResponseEntity.notFound().build();
    }

    // ------------------------------------------------------------- mapping

    static CsrMatrix toMatrix(CsrMatrixDto dto) {
        if (dto == null) {
            throw new MatrixValidationException(ErrorCode.DIMENSION_MISMATCH, "matrix is required");
        }
        return new CsrMatrix(dto.n, dto.rowPtr, dto.colIndices, dto.values);
    }

    static SolverOptions toOptions(SolveRequestDto req) {
        int nev = req.numEigenvalues != null ? req.numEigenvalues : 1;
        double tol = req.tolerance != null ? req.tolerance : 1e-8;
        int maxIter = req.maxIterations != null ? req.maxIterations : 300;
        long seed = req.seed != null ? req.seed : 42L;
        int snapshotEvery = req.snapshotEvery != null ? req.snapshotEvery : 25;
        return new SolverOptions(nev, tol, maxIter, req.intervalMin, req.intervalMax,
                seed, snapshotEvery);
    }

    static SolveResponseDto toResponse(String taskId, EigenResult r) {
        SolveResponseDto dto = new SolveResponseDto();
        dto.taskId = taskId;
        dto.status = r.status().name();
        dto.errorCode = r.errorCode() != null ? r.errorCode().name() : null;
        dto.message = r.message();
        dto.totalIterations = r.totalIterations();
        dto.wallMillis = r.wallMillis();
        dto.eigenpairs = r.eigenpairs().stream().map(p -> {
            SolveResponseDto.EigenPairDto d = new SolveResponseDto.EigenPairDto();
            d.eigenvalue = p.eigenvalue();
            d.eigenvector = p.eigenvector();
            d.residualNorm = p.residualNorm();
            d.converged = p.converged();
            d.iterations = p.iterations();
            return d;
        }).toList();
        return dto;
    }
}
