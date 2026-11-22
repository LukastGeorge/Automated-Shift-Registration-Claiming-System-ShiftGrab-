package com.shiftgrab.shared.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.shiftgrab.ApiTestSupport;

/**
 * SG-65: authorization must happen before input validation. Otherwise the wrong role receives
 * {@code 400 VALIDATION_FAILED} with the field list, which discloses the endpoint's input rules.
 */
class AuthorizationOrderTests extends ApiTestSupport {

    static Stream<Arguments> managerEndpointsWithInvalidInput() {
        return Stream.of(
                Arguments.of("create shift", post("/api/v1/shifts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}")),
                Arguments.of("edit shift", patch("/api/v1/shifts/1").contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", "\"0\"").content("{\"maxSlots\":0}")),
                Arguments.of("cancel shift", post("/api/v1/shifts/1/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + "x".repeat(600) + "\"}")),
                Arguments.of("create template", post("/api/v1/shift-templates").contentType(MediaType.APPLICATION_JSON)
                        .content("{}")),
                Arguments.of("generate", post("/api/v1/shift-templates/abc/generate")),
                Arguments.of("list users", get("/api/v1/users").param("size", "1000")),
                Arguments.of("set rate", put("/api/v1/users/1/hourly-rate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hourlyRate\":-1}")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("managerEndpointsWithInvalidInput")
    @DisplayName("STAFF gets 403, not a validation error, on manager endpoints")
    void staffIsRejectedBeforeValidation(String name, MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request.with(staff()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("managerEndpointsWithInvalidInput")
    @DisplayName("MANAGER still gets the validation error for the same invalid input")
    void managerStillGetsValidationErrors(String name, MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request.with(manager())).andExpect(status().is4xxClientError())
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == 403 || status == 401) {
                        throw new AssertionError(name + " was refused for a manager: " + status);
                    }
                });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("staffEndpoints")
    @DisplayName("MANAGER gets 403 on staff-only endpoints")
    void managerIsRejectedOnStaffEndpoints(String name, MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request.with(manager())).andExpect(status().isForbidden());
    }

    static Stream<Arguments> staffEndpoints() {
        return Stream.of(
                Arguments.of("claim", post("/api/v1/shifts/abc/registrations")),
                Arguments.of("cancel registration", delete("/api/v1/registrations/abc")));
    }
}
