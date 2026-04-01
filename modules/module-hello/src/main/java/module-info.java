import dev.nklip.javacraft.modules.hello.HelloService;
import dev.nklip.javacraft.modules.hello.impl.HelloServiceImpl;

module module.hello {
    exports dev.nklip.javacraft.modules.hello;
    provides HelloService with HelloServiceImpl;
}