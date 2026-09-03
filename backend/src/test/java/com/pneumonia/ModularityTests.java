package com.pneumonia;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Verifies the module boundaries declared by package layout under {@code com.pneumonia}
 * (infra, users, xray) are not violated - no cyclic dependencies, no reaching into another
 * module's internals. Pure static introspection of compiled classes: no Spring context, no
 * database, no broker required.
 */
class ModularityTests {

	static final ApplicationModules MODULES = ApplicationModules.of(Application.class);

	@Test
	void modulesAreConsistent() {
		MODULES.verify();
	}

	@Test
	void printModuleStructure() {
		System.out.println(MODULES);
	}
}
