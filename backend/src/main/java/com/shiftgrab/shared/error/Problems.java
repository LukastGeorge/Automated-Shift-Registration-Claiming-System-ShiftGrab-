package com.shiftgrab.shared.error;

import java.net.URI;

import org.springframework.http.ProblemDetail;

/** Factory for problem responses so every error has the same shape: status, title, detail, code, instance. */
public final class Problems {

    public static final String CODE = "code";

    private Problems() {
    }

    public static ProblemDetail of(ErrorCode code, String detail, String path) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setTitle(code.title());
        problem.setProperty(CODE, code.name());
        if (path != null) {
            problem.setInstance(URI.create(path));
        }
        return problem;
    }
}
