package com.github.guoyyit.sparseeigensolverjava.web;

import com.github.guoyyit.sparseeigensolverjava.model.EigenRequest;
import com.github.guoyyit.sparseeigensolverjava.model.EigenResponse;
import com.github.guoyyit.sparseeigensolverjava.service.EigenSolverService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST endpoints for local eigensolve calls. */
@RestController
@RequestMapping("/api")
public class EigenSolverController {

    private final EigenSolverService service;

    public EigenSolverController(EigenSolverService service) {
        this.service = service;
    }

    @PostMapping("/eigensolve")
    public EigenResponse solve(@RequestBody EigenRequest request,
                               @RequestParam(name = "wait", defaultValue = "false") boolean wait) {
        return service.submit(request, wait);
    }

    @GetMapping("/eigensolve/{id}")
    public EigenResponse status(@PathVariable("id") String id) {
        return service.getStatus(id);
    }

    @PostMapping("/eigensolve/{id}/cancel")
    public EigenResponse cancel(@PathVariable("id") String id) {
        return service.cancel(id);
    }
}
