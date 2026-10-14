package io.kestra.plugin.graalvm.js;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class EvalTest {
    @Test
    void preservesOpaqueInternalStorageUri() throws Exception {
        String uri = "kestra:///" + java.util.UUID.randomUUID() + ".csv";
        RunContext runContext = runContextFactory.of(Map.of("inputs", Map.of("uri", uri)));
        Eval task = Eval.builder()
            .id(java.util.UUID.randomUUID().toString())
            .type(Eval.class.getName())
            .script(Property.ofExpression("({uri: '{{ inputs.uri }}'})"))
            .outputs(Property.ofValue(List.of("uri")))
            .build();

        try {
            assertThat(task.run(runContext).getOutputs().get("uri"), is(uri));
        } finally {
            runContext.cleanup();
        }
    }

    @Inject
    private RunContextFactory runContextFactory;

    private Eval evalOf(String script) {
        return Eval.builder()
            .id("unit-test")
            .type(Eval.class.getName())
            .script(Property.ofValue(script))
            .build();
    }

    @Test
    void denyRuntimeLookup() {
        RunContext runContext = runContextFactory.of();
        Eval task = evalOf("Java.type('java.lang.Runtime').getRuntime().exec('id')");
        assertThrows(Exception.class, () -> task.run(runContext));
    }

    @Test
    void denyProcessBuilderLookup() {
        RunContext runContext = runContextFactory.of();
        Eval task = evalOf("new (Java.type('java.lang.ProcessBuilder'))(['id']).start()");
        assertThrows(Exception.class, () -> task.run(runContext));
    }

    @Test
    void denySystemLookup() {
        RunContext runContext = runContextFactory.of();
        Eval task = evalOf("Java.type('java.lang.System').getenv()");
        assertThrows(Exception.class, () -> task.run(runContext));
    }

    @Test
    void denyReflectionBypassViaClassLoader() {
        // The key bypass a lookup-only denylist misses: obtain a Class indirectly and use its
        // ClassLoader to load a denied class, then reflectively invoke it.
        RunContext runContext = runContextFactory.of();
        Eval task = evalOf(
            """
            var rt = ''.getClass().getClassLoader().loadClass('java.lang.Runtime');
            rt.getMethod('getRuntime').invoke(null);"""
        );
        assertThrows(Exception.class, () -> task.run(runContext));
    }

    @Test
    void denyReflectionBypassViaClassForName() {
        RunContext runContext = runContextFactory.of();
        Eval task = evalOf("Java.type('java.lang.Class').forName('java.lang.Runtime')");
        assertThrows(Exception.class, () -> task.run(runContext));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "java.io.File", "java.io.FileInputStream", "java.io.FileOutputStream", "java.io.FileReader",
        "java.io.FileWriter", "java.io.RandomAccessFile", "java.io.PrintStream", "java.io.PrintWriter",
        "java.util.Formatter", "java.util.zip.ZipFile", "java.util.jar.JarFile", "java.util.logging.FileHandler",
        "java.nio.file.Files", "java.nio.file.Path", "java.nio.file.Paths", "java.nio.file.FileSystems",
        "java.nio.channels.FileChannel", "java.lang.foreign.Linker", "java.lang.management.ManagementFactory",
        "java.util.prefs.Preferences", "java.beans.XMLDecoder", "java.beans.Statement", "java.beans.Expression",
        "java.sql.DriverManager", "java.lang.ModuleLayer", "java.lang.Module", "java.lang.module.ModuleFinder",
        "java.util.ServiceLoader", "java.io.ObjectInputStream", "java.awt.Toolkit", "java.awt.Font",
        "java.awt.image.PixelGrabber", "java.util.spi.ToolProvider",
        "io.kestra.core.models.tasks.runners.ScriptService", "io.kestra.core.models.tasks.runners.PluginUtilsService"
    })
    void denyFileAccessClassLookup(String className) {
        RunContext runContext = runContextFactory.of();
        Eval task = evalOf("Java.type('%s')".formatted(className));
        assertThrows(Exception.class, () -> task.run(runContext));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // Path/File from Kestra APIs must stay opaque
        "runContext.workingDir().path().resolve('../..').toString()",
        "runContext.workingDir().path().getFileSystem()",
        "runContext.workingDir().createTempFile().toFile().getParentFile()",
        "runContext.workingDir().createTempFile().toFile().getPath()"
    })
    void denyFileAccessThroughKestraPaths(String script) {
        RunContext runContext = runContextFactory.of();
        Eval task = evalOf(script);
        assertThrows(Exception.class, () -> task.run(runContext));
    }

    @Test
    void denyAddingJarToClasspath() throws Exception {
        // a jar written to the working dir would otherwise run as unrestricted host code
        RunContext runContext = runContextFactory.of();
        try (var jar = new java.util.jar.JarOutputStream(Files.newOutputStream(runContext.workingDir().path().resolve("evil.jar")))) {
            jar.putNextEntry(new java.util.zip.ZipEntry("empty.txt"));
        }

        var exception = assertThrows(PolyglotException.class, () -> evalOf("Java.addToClasspath('evil.jar')").run(runContext));
        assertThat(exception.getMessage(), containsString("not allowed"));
    }

    @Test
    void denyFileUrlFromKestraUri(@TempDir Path outsideDir) throws Exception {
        // a URI returned by a Kestra API must not open a file: URL on the host
        var secret = Files.writeString(outsideDir.resolve("secret.txt"), "secret");
        RunContext runContext = runContextFactory.of();
        Eval task = evalOf("""
            var ByteArrayInputStream = Java.type('java.io.ByteArrayInputStream');
            var bytes = Java.type('java.nio.charset.StandardCharsets').UTF_8.encode('x');
            var uri = runContext.storage().putFile(new ByteArrayInputStream(bytes.array(), 0, bytes.limit()), 'x.txt');
            uri.resolve('%s').toURL().openStream().read()""".formatted(secret.toUri()));

        assertThrows(PolyglotException.class, () -> task.run(runContext));
    }

    @Test
    void denyJdkToolsWritingOutside(@TempDir Path outsideDir) throws Exception {
        // the jar tool runs in the worker and writes to any path without going through the guest file system
        var secret = Files.writeString(outsideDir.resolve("secret.txt"), "secret");
        var target = outsideDir.resolve("escaped.jar");
        RunContext runContext = runContextFactory.of();
        Eval task = evalOf("""
            var tool = Java.type('java.util.spi.ToolProvider').findFirst('jar').get();
            tool['run(java.io.PrintWriter,java.io.PrintWriter,java.lang.String[])'](null, null, ['cf', '%s', '-C', '%s', '%s']);"""
            .formatted(target, outsideDir, secret.getFileName()));

        assertThrows(PolyglotException.class, () -> task.run(runContext));
        assertThat(Files.exists(target), is(false));
    }

    @Test
    void denyHeapDumpThroughManagementBeans() {
        // dumpHeap can write the JVM heap to any path
        RunContext runContext = runContextFactory.of();
        Eval task = evalOf("""
            var server = Java.type('java.lang.management.ManagementFactory').getPlatformMBeanServer();
            var name = Array.from(server.queryNames(null, null)).find(n => n.toString().includes('HotSpotDiagnostic'));
            server.invoke(name, 'dumpHeap', ['/tmp/kestra-heap.hprof', true], ['java.lang.String', 'boolean']);""");
        assertThrows(Exception.class, () -> task.run(runContext));
    }

    @Test
    void writesOutputFileWithoutFileClasses() throws Exception {
        RunContext runContext = runContextFactory.of();
        Eval task = Eval.builder()
            .id("unit-test")
            .type(Eval.class.getName())
            .script(Property.ofValue("""
                var ByteArrayInputStream = Java.type('java.io.ByteArrayInputStream');
                var StandardCharsets = Java.type('java.nio.charset.StandardCharsets');
                var bytes = StandardCharsets.UTF_8.encode('Hello World');
                var out = runContext.storage().putFile(new ByteArrayInputStream(bytes.array(), 0, bytes.limit()), 'out.txt');
                ({out: out})"""))
            .outputs(Property.ofValue(List.of("out")))
            .build();

        var out = (URI) task.run(runContext).getOutputs().get("out");

        assertThat(out.toString(), startsWith("kestra:///"));
        try (var stream = runContext.storage().getFile(out)) {
            assertThat(new String(stream.readAllBytes()), is("Hello World"));
        }
    }

    // load() is the only GraalJS builtin that reads files

    @Test
    void loadsCodeFromInsideWorkingDir() throws Exception {
        RunContext runContext = runContextFactory.of();
        Files.writeString(runContext.workingDir().path().resolve("helper.js"), "var helper = 'hello';");

        Eval task = Eval.builder()
            .id("unit-test")
            .type(Eval.class.getName())
            .script(Property.ofValue("load('helper.js'); ({content: helper})"))
            .outputs(Property.ofValue(List.of("content")))
            .build();

        var runOutput = task.run(runContext);

        assertThat(runOutput.getOutputs().get("content"), is("hello"));
    }

    @Test
    void loadingCodeFromOutsideWorkingDirIsDenied(@TempDir Path outsideDir) throws Exception {
        var evil = Files.writeString(outsideDir.resolve("evil.js"), "throw new Error('pwned');");
        RunContext runContext = runContextFactory.of();
        Files.createSymbolicLink(runContext.workingDir().path().resolve("link.js"), evil);
        Files.createSymbolicLink(runContext.workingDir().path().resolve("linkDir"), outsideDir);

        assertLoadDenied(runContext, "load('%s')".formatted(evil));
        assertLoadDenied(runContext, "load('" + "../".repeat(30) + evil.toRealPath().toString().substring(1) + "')");
        assertLoadDenied(runContext, "load('link.js')");
        assertLoadDenied(runContext, "load('linkDir/evil.js')");
    }

    private void assertLoadDenied(RunContext runContext, String script) {
        var exception = assertThrows(PolyglotException.class, () -> evalOf(script).run(runContext));
        assertThat(exception.getMessage(), containsString("only access files inside the task working directory"));
    }

    @Test
    void runValue() throws Exception {
        RunContext runContext = runContextFactory.of();

        Eval task = Eval.builder()
            .script(Property.ofValue(
                """
                var BigDecimal = Java.type('java.math.BigDecimal');
                BigDecimal.valueOf(10).pow(20)"""
            ))
            .build();

        var runOutput = task.run(runContext);
        assertThat(runOutput, notNullValue());
        assertThat(runOutput.getOutputs(), nullValue());
        assertThat(runOutput.getResult(), is(new BigDecimal("100000000000000000000")));
    }

    @Test
    void runMember() throws Exception {
        RunContext runContext = runContextFactory.of();

        Eval task = Eval.builder()
            .script(Property.ofValue(
                "({ id   : 42, text : '42', arr  : [1,42,3] })"
            ))
            .outputs(Property.ofValue(List.of("id", "text")))
            .build();

        var runOutput = task.run(runContext);
        assertThat(runOutput, notNullValue());
        assertThat(runOutput.getResult(), nullValue());
        assertThat(runOutput.getOutputs(), aMapWithSize(2));
        assertThat(runOutput.getOutputs().get("id"), is(42));
        assertThat(runOutput.getOutputs().get("text"), is("42"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void runFunction() throws Exception {
        RunContext runContext = runContextFactory.of();

        Eval task = Eval.builder()
            .id("unit-test")
            .type(Eval.class.getName())
            .script(Property.ofValue(
                """
                    (function() {
                    var Counter = Java.type('io.kestra.core.models.executions.metrics.Counter');
                    var ByteArrayInputStream = Java.type('java.io.ByteArrayInputStream');
                    var StandardCharsets = Java.type('java.nio.charset.StandardCharsets');

                    runContext.metric(Counter.of('total', 666, 'name', 'bla'));

                    map = {'test': 'here'};
                    var bytes = StandardCharsets.UTF_8.encode('Hello World');
                    var content = new ByteArrayInputStream(bytes.array(), 0, bytes.limit());

                    out = runContext.storage().putFile(content, 'out.txt');
                    return {"map": map, "out": out};
                    })"""
            ))
            .outputs(Property.ofValue(List.of("map", "out")))
            .build();

        var runOutput = task.run(runContext);
        assertThat(runOutput, notNullValue());
        assertThat(runOutput.getResult(), nullValue());
        assertThat(runOutput.getOutputs(), aMapWithSize(2));
        assertThat((Map<String, Object>) runOutput.getOutputs().get("map"), aMapWithSize(1));
        assertThat(((Map<String, Object>) runOutput.getOutputs().get("map")).get("test"), is("here"));
        assertThat(((URI) runOutput.getOutputs().get("out")).toString(), startsWith("kestra:///"));
    }
}