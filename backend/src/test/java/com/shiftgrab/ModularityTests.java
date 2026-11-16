package com.shiftgrab;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * ADR-0001: module boundaries are enforced by a test, not by convention.
 * Fails the build on cycles or on access to another module's internal packages.
 */
class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(ShiftGrabApplication.class);

    @Test
    void verifiesModuleStructure() {
        modules.verify();
    }

    @Test
    void writesModuleDocumentation() {
        new Documenter(modules)
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml();
    }
}
