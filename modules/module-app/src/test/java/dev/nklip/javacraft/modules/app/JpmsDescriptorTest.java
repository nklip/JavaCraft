package dev.nklip.javacraft.modules.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.file.Path;
import dev.nklip.javacraft.modules.hello.HelloService;
import dev.nklip.javacraft.modules.util.Util;
import org.junit.jupiter.api.Test;

class JpmsDescriptorTest {

    @Test
    void testModuleAppShouldDeclareUsesForHelloService() throws Exception {
        ModuleDescriptor appDescriptor = descriptorFor(App.class, "module.app");
        assertTrue(appDescriptor.uses().contains(HelloService.class.getName()));
    }

    @Test
    void testModuleHelloShouldProvideHelloServiceImplementation() throws Exception {
        ModuleDescriptor helloDescriptor = descriptorFor(HelloService.class, "module.hello");

        ModuleDescriptor.Provides provides = helloDescriptor.provides()
                .stream()
                .filter(item -> item.service().equals(HelloService.class.getName()))
                .findFirst()
                .orElseThrow();

        assertTrue(provides.providers().contains("dev.nklip.javacraft.modules.hello.impl.HelloServiceImpl"));
    }

    @Test
    void testModuleUtilShouldExportUtilPackageOnlyToModuleApp() throws Exception {
        ModuleDescriptor utilDescriptor = descriptorFor(Util.class, "module.util");

        ModuleDescriptor.Exports utilExport = utilDescriptor.exports()
                .stream()
                .filter(exported -> exported.source().equals("dev.nklip.javacraft.modules.util"))
                .findFirst()
                .orElseThrow();

        assertTrue(utilExport.isQualified());
        assertEquals(1, utilExport.targets().size());
        assertTrue(utilExport.targets().contains("module.app"));
    }

    @Test
    void testModuleUtilShouldNotExportSecretPackage() throws Exception {
        ModuleDescriptor utilDescriptor = descriptorFor(Util.class, "module.util");

        assertTrue(utilDescriptor.exports()
                .stream()
                .noneMatch(exported -> exported.source().equals("dev.nklip.javacraft.modules.util.secret")));
    }

    private ModuleDescriptor descriptorFor(Class<?> sourceType, String expectedModuleName) throws Exception {
        Path location = Path.of(sourceType.getProtectionDomain().getCodeSource().getLocation().toURI());
        ModuleFinder moduleFinder = ModuleFinder.of(location);
        return moduleFinder.find(expectedModuleName).orElseThrow().descriptor();
    }
}
