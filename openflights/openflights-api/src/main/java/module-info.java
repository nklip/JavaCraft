module dev.nklip.javacraft.openflights.api {
    // don't export 'dev.nklip.javacraft.openflights.api.parser' package
    // means OpenFlightsValueParser class is not accessible
    // outside 'dev.nklip.javacraft.openflights.api' package
    exports dev.nklip.javacraft.openflights.api;
    exports dev.nklip.javacraft.openflights.api.kafka;
}