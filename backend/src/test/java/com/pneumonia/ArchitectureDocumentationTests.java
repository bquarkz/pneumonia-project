package com.pneumonia;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.docs.Documenter;

/**
 * Generates architecture documentation (module PlantUML/C4 diagrams and a component overview)
 * from the real, compiled module structure under {@code target/spring-modulith-docs/} - per
 * DEC-0001 item 16. {@code spring-modulith-docs} arrives transitively via
 * {@code spring-modulith-starter-test}; no separate dependency was needed.
 *
 * <p>No assertions beyond "this ran without throwing": the point of this test is the
 * generated output as a side effect, not a proposition to verify.
 */
class ArchitectureDocumentationTests {

	@Test
	void writesModuleDocumentation() {
		new Documenter(ModularityTests.MODULES)
			.writeModulesAsPlantUml()
			.writeIndividualModulesAsPlantUml()
			.writeModuleCanvases();
	}
}
