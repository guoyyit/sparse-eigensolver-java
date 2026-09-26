package com.github.guoyyit.sparseeigensolverjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end REST behavior: solve, validation error codes, async lifecycle.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EigenApiTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("solver.snapshot-dir", () -> tempDir.resolve("snap").toString());
        r.add("solver.audit-file", () -> tempDir.resolve("audit.log").toString());
        r.add("solver.max-concurrent-tasks", () -> "2");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper om;

    /** Build a JSON request for the 1D Laplacian of size n. */
    private String laplacianRequest(int n, int nev, double tol, int maxIter, long seed) {
        List<Integer> rowPtr = new ArrayList<>();
        List<Integer> cols = new ArrayList<>();
        List<Double> vals = new ArrayList<>();
        rowPtr.add(0);
        for (int i = 0; i < n; i++) {
            cols.add(i);
            vals.add(2.0);
            if (i + 1 < n) {
                cols.add(i + 1);
                vals.add(-1.0);
            }
            rowPtr.add(cols.size());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\"matrix\":{\"n\":").append(n)
                .append(",\"rowPtr\":").append(rowPtr.toString())
                .append(",\"colIndices\":").append(cols.toString())
                .append(",\"values\":").append(vals.toString()).append("}")
                .append(",\"numEigenvalues\":").append(nev)
                .append(",\"tolerance\":").append(tol)
                .append(",\"maxIterations\":").append(maxIter)
                .append(",\"seed\":").append(seed)
                .append("}");
        return sb.toString();
    }

    @Test
    void synchronousSolveReturnsConvergedPairs() throws Exception {
        MvcResult res = mvc.perform(post("/api/eigen/solve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(laplacianRequest(100, 3, 1e-9, 200, 42)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = om.readTree(res.getResponse().getContentAsString());
        assertEquals("CONVERGED", body.get("status").asText());
        assertEquals(3, body.get("eigenpairs").size());
        double first = body.get("eigenpairs").get(0).get("eigenvalue").asDouble();
        double expected = 2 - 2 * Math.cos(Math.PI / 101);
        assertEquals(expected, first, 1e-6);
        // Every pair carries residual evidence and converged flag.
        for (JsonNode p : body.get("eigenpairs")) {
            assertTrue(p.get("residualNorm").asDouble() < 1e-7);
            assertTrue(p.get("converged").asBoolean());
            assertEquals(100, p.get("eigenvector").size());
        }
    }

    @Test
    void duplicateIndexRejectedWithDistinctCode() throws Exception {
        String body = "{\"matrix\":{\"n\":2,\"rowPtr\":[0,2,3],"
                + "\"colIndices\":[0,0,1],\"values\":[1,2,1]},\"numEigenvalues\":1}";
        MvcResult res = mvc.perform(post("/api/eigen/solve")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andReturn();
        JsonNode json = om.readTree(res.getResponse().getContentAsString());
        assertEquals("DUPLICATE_INDEX", json.get("errorCode").asText());
        assertFalse(json.get("message").asText().isEmpty());
    }

    @Test
    void lowerTriangleRejectedWithDistinctCode() throws Exception {
        String body = "{\"matrix\":{\"n\":2,\"rowPtr\":[0,1,2],"
                + "\"colIndices\":[0,0],\"values\":[1,1]},\"numEigenvalues\":1}";
        MvcResult res = mvc.perform(post("/api/eigen/solve")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andReturn();
        JsonNode json = om.readTree(res.getResponse().getContentAsString());
        assertEquals("ASYMMETRIC_STRUCTURE", json.get("errorCode").asText());
    }

    @Test
    void nonFiniteValueRejectedWithDistinctCode() throws Exception {
        String body = "{\"matrix\":{\"n\":2,\"rowPtr\":[0,1,2],"
                + "\"colIndices\":[0,1],\"values\":[1,\"NaN\"]},\"numEigenvalues\":1}";
        MvcResult res = mvc.perform(post("/api/eigen/solve")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andReturn();
        JsonNode json = om.readTree(res.getResponse().getContentAsString());
        assertEquals("NON_FINITE_VALUE", json.get("errorCode").asText());
    }

    @Test
    void nonConvergenceIsExplicitFailureNotWrappedSuccess() throws Exception {
        // 1 iteration, impossible tolerance: must fail honestly.
        MvcResult res = mvc.perform(post("/api/eigen/solve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(laplacianRequest(400, 5, 1e-15, 1, 7)))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();
        JsonNode body = om.readTree(res.getResponse().getContentAsString());
        assertEquals("FAILED", body.get("status").asText());
        assertEquals("NOT_CONVERGED", body.get("errorCode").asText());
        assertEquals(0, body.get("eigenpairs").size(), "no success-wrapped approximations");
    }

    @Test
    void asyncLifecycleSubmitPollCancel() throws Exception {
        // Submit a long-running task.
        MvcResult submit = mvc.perform(post("/api/eigen/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(laplacianRequest(3000, 8, 1e-14, 100000, 3)))
                .andExpect(status().isAccepted())
                .andReturn();
        String taskId = om.readTree(submit.getResponse().getContentAsString())
                .get("taskId").asText();

        // Poll until running (or already done on a very fast machine).
        String status = "";
        for (int i = 0; i < 2000; i++) {
            MvcResult poll = mvc.perform(get("/api/eigen/" + taskId))
                    .andExpect(status().isOk()).andReturn();
            status = om.readTree(poll.getResponse().getContentAsString())
                    .get("status").asText();
            if (status.equals("RUNNING") || status.equals("CONVERGED")) {
                break;
            }
            Thread.sleep(5);
        }
        if (status.equals("RUNNING")) {
            mvc.perform(delete("/api/eigen/" + taskId))
                    .andExpect(status().isAccepted());
            // Eventually terminal with CANCELLED and no partial results.
            String finalStatus = "";
            for (int i = 0; i < 2000; i++) {
                MvcResult poll = mvc.perform(get("/api/eigen/" + taskId))
                        .andExpect(status().isOk()).andReturn();
                JsonNode body = om.readTree(poll.getResponse().getContentAsString());
                finalStatus = body.get("status").asText();
                if (body.has("errorCode") && !body.get("errorCode").isNull()) {
                    break;
                }
                Thread.sleep(5);
            }
            assertEquals("CANCELLED", finalStatus);
        }
        // Unknown id -> 404.
        mvc.perform(get("/api/eigen/no-such-task")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/eigen/no-such-task")).andExpect(status().isNotFound());
    }

    @Test
    void reproducibleAcrossIdenticalRequests() throws Exception {
        MvcResult r1 = mvc.perform(post("/api/eigen/solve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(laplacianRequest(80, 2, 1e-9, 100, 9)))
                .andExpect(status().isOk()).andReturn();
        MvcResult r2 = mvc.perform(post("/api/eigen/solve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(laplacianRequest(80, 2, 1e-9, 100, 9)))
                .andExpect(status().isOk()).andReturn();
        JsonNode b1 = om.readTree(r1.getResponse().getContentAsString());
        JsonNode b2 = om.readTree(r2.getResponse().getContentAsString());
        assertEquals(b1.get("eigenpairs"), b2.get("eigenpairs"),
                "identical input+seed must reproduce identical eigenpairs");
    }
}
