package com.github.guoyyit.sparseeigensolverjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "solver.snapshot-dir=target/test-snapshots-web")
@AutoConfigureMockMvc
class WebApiTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void badJsonReturnsInvalidConfig() throws Exception {
        mvc.perform(post("/api/eigensolve").contentType(MediaType.APPLICATION_JSON)
                        .content("{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CONFIG"));
    }

    @Test
    void duplicateIndexReturnsDistinctErrorCode() throws Exception {
        String body = """
                {
                  "requestId": "web-dup",
                  "dimension": 3,
                  "rowPtr": [0, 2, 3, 4],
                  "colIndex": [0, 0, 1, 2],
                  "values": [1, 1, 1, 1],
                  "nev": 1
                }
                """;
        mvc.perform(post("/api/eigensolve").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_INDEX"));
    }

    @Test
    void nonSymmetricReturnsDistinctErrorCode() throws Exception {
        String body = """
                {
                  "requestId": "web-sym",
                  "dimension": 2,
                  "rowPtr": [0, 1, 2],
                  "colIndex": [0, 0],
                  "values": [1, 1],
                  "nev": 1
                }
                """;
        mvc.perform(post("/api/eigensolve").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NON_SYMMETRIC_UPPER_TRIANGLE"));
    }

    @Test
    void validSolveSucceedsWithDiagnostics() throws Exception {
        String body = """
                {
                  "requestId": "web-ok",
                  "dimension": 10,
                  "rowPtr": [0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 19],
                  "colIndex": [0,1, 1,2, 2,3, 3,4, 4,5, 5,6, 6,7, 7,8, 8,9, 9],
                  "values": [2,-1, 2,-1, 2,-1, 2,-1, 2,-1, 2,-1, 2,-1, 2,-1, 2,-1, 2],
                  "nev": 2,
                  "tolerance": 1e-9,
                  "maxIterations": 10
                }
                """;
        mvc.perform(post("/api/eigensolve").param("wait", "true")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONVERGED"))
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[0].converged").value(true))
                .andExpect(jsonPath("$.signConvention").exists())
                .andExpect(jsonPath("$.normalization").exists())
                .andExpect(jsonPath("$.algorithm").exists());
    }

    @Test
    void unknownJobReturns404() throws Exception {
        mvc.perform(get("/api/eigensolve/no-such-job-xyz"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
    }
}
