// when someone does 'requires' module.util, they will have access to the public types
// in our 'dev.nklip.javacraft.modules.util', but not any other package.
module module.util {
    // I declare a 'dev.nklip.javacraft.modules.util' package as exported.
    // but, we also list which modules we are allowing to import this package as a 'requires'.

    // This means the compiler needs module.app on the module path to validate the to clause,
    // but module-util has no Maven dependency on module-app — it only exports to it.
    // During compilation of module-util in isolation, the compiler can't find module.app and emits the warning.

    // This is a known javac limitation — qualified exports reference modules that aren't necessarily
    // on the compile-time module path. Since module-util doesn't actually depend on module-app (it's the reverse)
    // You could see a warning: 'module not found: module.app'
    // That's okay.
    exports dev.nklip.javacraft.modules.util to module.app;
}