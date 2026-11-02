package com.policyscript;

import com.policyscript.config.ConfigLoader;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Guards against a broken Maven/Surefire runtime classpath. These are application
 * classes/resources required by the original compiler and academic Access Control screen.
 */
class BuildClasspathSmokeTest {
    @Test
    void testRuntimeCanLoadCoreAstAndOptimizerTypes() throws Exception {
        assertNotNull(Class.forName("com.policyscript.ast.Ast"));
        assertNotNull(Class.forName("com.policyscript.ast.Conditions$Constant"));
        assertNotNull(Class.forName("com.policyscript.optimizer.Optimizer"));
    }

    @Test
    void testRuntimeCanLoadTimeInputParsers() throws Exception {
        assertNotNull(Class.forName("com.policyscript.util.MinuteTimeParser"));
        assertNotNull(Class.forName("com.policyscript.util.AccessTimeInputParser"));
    }

    @Test
    void testRuntimeCanLoadBundledConfigurationResource() throws Exception {
        try (InputStream input = ConfigLoader.class.getResourceAsStream("/config/roles-resources.json")) {
            assertNotNull(input, "Bundled /config/roles-resources.json must be visible to the test runtime classloader");
            assertNotNull(ConfigLoader.loadResource("/config/roles-resources.json"));
        }
    }
}
