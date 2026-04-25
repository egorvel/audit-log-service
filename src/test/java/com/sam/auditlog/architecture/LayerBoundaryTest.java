package com.sam.auditlog.architecture;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Enforces the package-layer boundaries documented in AGENTS.md.
 *
 * <p>The arrows are: controller -> service -> repository, with converter / dto / model / util
 * usable as supporting layers. Anything else (e.g. a controller reaching into the repository
 * directly) is a violation and fails the build.
 */
@AnalyzeClasses(
        packages = "com.sam.auditlog",
        importOptions = {ImportOption.DoNotIncludeTests.class})
class LayerBoundaryTest {

    @ArchTest
    static final ArchRule layered_architecture_is_respected =
            layeredArchitecture()
                    .consideringAllDependencies()
                    .layer("Controller")
                    .definedBy("com.sam.auditlog.controller..")
                    .layer("Service")
                    .definedBy("com.sam.auditlog.service..")
                    .layer("Repository")
                    .definedBy("com.sam.auditlog.repository..")
                    .layer("Converter")
                    .definedBy("com.sam.auditlog.converter..")
                    .layer("Dto")
                    .definedBy("com.sam.auditlog.dto..")
                    .layer("Model")
                    .definedBy("com.sam.auditlog.model..")
                    .layer("Util")
                    .definedBy("com.sam.auditlog.util..")
                    // Config is reserved in AGENTS.md for Spring @Configuration classes;
                    // it can legitimately be empty until one is added, so allow that.
                    .optionalLayer("Config")
                    .definedBy("com.sam.auditlog.config..")

                    // Top of the stack: nothing else may depend on the HTTP layer.
                    .whereLayer("Controller")
                    .mayNotBeAccessedByAnyLayer()

                    // Service is reached only from controllers.
                    .whereLayer("Service")
                    .mayOnlyBeAccessedByLayers("Controller")

                    // Repositories are an implementation detail of the service layer.
                    .whereLayer("Repository")
                    .mayOnlyBeAccessedByLayers("Service")

                    // Converters translate between DTOs and entities; only callers of
                    // those translations should depend on them.
                    .whereLayer("Converter")
                    .mayOnlyBeAccessedByLayers("Controller", "Service")

                    // DTOs are part of the API surface; converters and the layers that
                    // hand them across the wire may use them.
                    .whereLayer("Dto")
                    .mayOnlyBeAccessedByLayers("Controller", "Service", "Converter")

                    // Util holds cross-cutting helpers (currently JSON support); only
                    // layers that actually need them may pull them in.
                    .whereLayer("Util")
                    .mayOnlyBeAccessedByLayers("Repository", "Service", "Controller", "Config")

                    // Model is the domain core; everything above it may depend on it,
                    // but it does not depend on anything else inside the project.
                    .whereLayer("Model")
                    .mayOnlyBeAccessedByLayers(
                            "Controller", "Service", "Repository", "Converter", "Dto", "Util");
}
