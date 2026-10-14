package io.kestra.plugin.graalvm.python;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.serializers.YamlParser;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.graalvm.ForkedJvm;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class EvalTest {
    @Test
    void uploadedCsvExampleReturnsDeclaredOutputsAfterLogging() throws Exception {
        Flow flow;
        try (var yaml = getClass().getResourceAsStream("/flows/read-uploaded-csv.yaml")) {
            flow = YamlParser.parse(new String(yaml.readAllBytes(), StandardCharsets.UTF_8), Flow.class);
        }
        RunContext uploadContext = runContextFactory.of();
        URI uploaded = uploadContext.storage().putFile(
            new ByteArrayInputStream("name,score\nAda,42\nGrace,99\n".getBytes(StandardCharsets.UTF_8)),
            UUID.randomUUID() + ".csv"
        );
        RunContext runContext = runContextFactory.of(Map.of("inputs", Map.of("csv_file", uploaded.toString())));
        try {
            var output = ((Eval) flow.getTasks().getFirst()).run(runContext);
            assertThat(output.getOutputs().get("row_count"), is(2));
            assertThat(output.getOutputs().get("total"), is(141));
        } finally {
            runContext.cleanup();
        }
    }

    @Test
    void shouldReadUploadedCsvFromRenderedInput() throws Exception {
        RunContext uploadContext = runContextFactory.of();
        URI uploaded = uploadContext.storage().putFile(
            new ByteArrayInputStream("name,score\nAda,42\nGrace,99\n".getBytes(StandardCharsets.UTF_8)),
            UUID.randomUUID() + ".csv"
        );
        RunContext runContext = runContextFactory.of(Map.of("inputs", Map.of("csv_file", uploaded.toString())));
        Eval task = Eval.builder()
            .id(UUID.randomUUID().toString())
            .type(Eval.class.getName())
            .script(Property.ofExpression("""
                import csv
                import os
                path = '{{ inputs.csv_file }}'
                second_path = '{{ inputs.csv_file }}'
                with open(second_path, 'r') as second_file:
                    duplicate_content = second_file.read()
                with open(path, 'r') as csvfile:
                    rows = list(csv.DictReader(csvfile))
                names = ', '.join(row['name'] for row in rows)
                duplicate_matches = duplicate_content == 'name,score\\nAda,42\\nGrace,99\\n'
                total = sum(int(row['score']) for row in rows)
                uri_unchanged = path == inputs['csv_file']
                file_size = os.stat(path).st_size
                """))
            .outputs(Property.ofValue(List.of("names", "total", "uri_unchanged", "duplicate_matches", "file_size")))
            .build();

        Path workingDirectory = runContext.workingDir().path();
        try {
            var output = task.run(runContext);
            assertThat(output.getOutputs().get("names"), is("Ada, Grace"));
            assertThat(output.getOutputs().get("total"), is(141));
            assertThat(output.getOutputs().get("uri_unchanged"), is(true));
            assertThat(output.getOutputs().get("file_size"), is(27));
            try (var files = Files.list(workingDirectory)) {
                var staged = files.toList();
                assertThat(staged.size(), is(1));
                assertThat(staged.getFirst().toRealPath().getParent(), is(workingDirectory.toRealPath()));
            }
            assertThat(output.getOutputs().get("duplicate_matches"), is(true));
        } finally {
            runContext.cleanup();
        }
        assertThat(Files.exists(workingDirectory), is(false));
        try (var original = uploadContext.storage().getFile(uploaded)) {
            assertThat(new String(original.readAllBytes(), StandardCharsets.UTF_8), is("name,score\nAda,42\nGrace,99\n"));
        }
    }

    @Test
    void preservesOpaqueStorageUriAccessedThroughBindings() throws Exception {
        String uri = "kestra:///" + UUID.randomUUID() + ".csv";
        RunContext runContext = runContextFactory.of(Map.of("inputs", Map.of("csv_file", uri)));
        Eval task = Eval.builder()
            .id(UUID.randomUUID().toString())
            .type(Eval.class.getName())
            .script(Property.ofValue("uri = inputs['csv_file']"))
            .outputs(Property.ofValue(List.of("uri")))
            .build();

        try {
            assertThat(task.run(runContext).getOutputs().get("uri"), is(uri));
        } finally {
            runContext.cleanup();
        }
    }

    @Test
    void shouldFailWhenReferencedStorageFileDoesNotExist() {
        RunContext runContext = runContextFactory.of(Map.of("inputs", Map.of("csv_file", "kestra:///" + UUID.randomUUID() + ".csv")));
        Eval task = Eval.builder()
            .id(UUID.randomUUID().toString())
            .type(Eval.class.getName())
            .script(Property.ofExpression("open('{{ inputs.csv_file }}').read()"))
            .build();

        Path workingDirectory = runContext.workingDir().path();
        try {
            var failure = assertThrows(PolyglotException.class, () -> task.run(runContext));
            assertThat(failure.isGuestException(), is(true));
            assertThat(failure.getMessage(), containsString("FileNotFoundError"));
        } finally {
            runContext.cleanup();
        }
        assertThat(Files.exists(workingDirectory), is(false));
    }

    // The issue's own repro script: these modules extract native/platform resources from the Python
    // stdlib and used to fail once the GraalPy user resource cache (~/.cache/org.graalvm.polyglot)
    // was unwritable, wiped, or corrupted (kestra-io/plugin-graalvm#40).
    static final String STDLIB_IMPORT_SCRIPT = """
        import json
        import ssl
        import urllib
        import http
        import email
        import sqlite3
        import lzma
        import zoneinfo
        """;

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void rejectModulePathTraversal() {
        RunContext runContext = runContextFactory.of();

        Eval task = Eval.builder()
            .id("unit-test")
            .type(Eval.class.getName())
            .modules(Property.ofValue(
                Map.of("../evil.py", "x = 1\n")
            ))
            .script(Property.ofValue("pass\n"))
            .build();

        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(exception.getMessage(), containsString("path separators"));
    }

    @Test
    void runValue() throws Exception {
        RunContext runContext = runContextFactory.of();

        Eval task = Eval.builder()
            .script(Property.ofValue(
                """
                import java.math.BigDecimal as BigDecimal
                BigDecimal.valueOf(10).pow(20)"""
            ))
            .build();

        var runOutput = task.run(runContext);
        assertThat(runOutput, notNullValue());
        assertThat(runOutput.getOutputs(), nullValue());
        assertThat(runOutput.getResult(), is(new BigDecimal("100000000000000000000")));
    }

    @Test
    void runFunction() throws Exception {
        RunContext runContext = runContextFactory.of();

        Eval task = Eval.builder()
            .id("unit-test")
            .type(Eval.class.getName())
            .script(Property.ofValue(
                """
                    import java
                    import java.io.ByteArrayInputStream as ByteArrayInputStream
                    # types other than one coming from the Java SDK must be defined this way
                    Counter = java.type("io.kestra.core.models.executions.metrics.Counter")
                    logger.info('Task started')
                    runContext.metric(Counter.of('total', 666, 'name', 'bla'))
                    map = {'test': 'here'}
                    content = ByteArrayInputStream('Hello World'.encode('utf-8'))
                    out = runContext.storage().putFile(content, 'out.txt')
                    """
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

    @Test
    void runFunctionWithModule() throws Exception {
        RunContext runContext = runContextFactory.of();

        Eval task = Eval.builder()
            .id("unit-test")
            .type(Eval.class.getName())
            .modules(Property.ofValue(
                Map.of("hello.py", """
                    def hello(name):
                      print("Hello " + name)
                    """)
            ))
            .script(Property.ofValue(
                """
                    import hello
                    hello.hello("Loïc")
                    """
            ))
            .build();

        var runOutput = task.run(runContext);
        assertThat(runOutput, notNullValue());
    }

    @Test
    void runFunctionWithModuleAndFileWrite() throws Exception {
        // Regression test: Eval#contextBuilder() takes a different path when `modules` is set
        // (GraalPyResources.contextBuilder(Path) instead of AbstractScript's default Context.newBuilder()).
        // Proves file I/O inside the working directory still works when combined with `modules`.
        RunContext runContext = runContextFactory.of();

        Eval task = Eval.builder()
            .id("unit-test")
            .type(Eval.class.getName())
            .modules(Property.ofValue(
                Map.of("hello.py", """
                    def hello(name):
                      return "Hello " + name
                    """)
            ))
            .script(Property.ofValue(
                """
                    import hello
                    import java.io.ByteArrayInputStream as ByteArrayInputStream
                    with open('out.txt', 'w') as f:
                        f.write(hello.hello("Kestra"))
                    with open('out.txt', 'rb') as f:
                        out = runContext.storage().putFile(ByteArrayInputStream(f.read()), 'out.txt')
                    """
            ))
            .outputs(Property.ofValue(List.of("out")))
            .build();

        var runOutput = task.run(runContext);
        assertThat(runOutput, notNullValue());
        assertThat(((URI) runOutput.getOutputs().get("out")).toString(), startsWith("kestra:///"));
    }

    @Test
    void stdlibImports() throws Exception {
        RunContext runContext = runContextFactory.of();

        Eval task = Eval.builder()
            .id("unit-test")
            .type(Eval.class.getName())
            .script(Property.ofValue(STDLIB_IMPORT_SCRIPT))
            .build();

        var runOutput = task.run(runContext);
        assertThat(runOutput, notNullValue());
    }

    @Test
    void stdlibImportsWithUnwritableDefaultResourceCache(@TempDir Path tempDir) throws Exception {
        // Reproduces the issue: with neither polyglot.engine.userResourceCache nor
        // polyglot.engine.resourcePath set, GraalVM falls back to $XDG_CACHE_HOME or
        // ${user.home}/.cache/org.graalvm.polyglot to extract the stdlib. Pointing user.home at a
        // regular file (not a directory) makes that OS-default location unusable regardless of OS
        // user/permissions (including root in CI). EngineHolder must default userResourceCache to a
        // writable path under java.io.tmpdir before that broken default is ever consulted.
        Path unwritableHome = tempDir.resolve("not-a-directory");
        Files.createFile(unwritableHome);

        ForkedJvm.run(
            List.of("-Duser.home=" + unwritableHome),
            StdlibImportForkMain.class,
            StdlibImportForkMain.SUCCESS_MARKER
        );
    }

    @Test
    void engineHolderFallsBackWhenResourceCacheDirIsUnwritable(@TempDir Path tempDir) throws Exception {
        // Forces Files.createDirectories(cacheDir) to fail inside EngineHolder.createEngine()'s static
        // field initializer by pre-creating the exact cache dir path as a regular file. The cache dir
        // name is now PID-suffixed (CWE-377 fix: unpredictable ahead of the forked process starting), so
        // the colliding file can only be created once the real PID is known -- the forked main therefore
        // waits for a "go" marker file (args[0]) before touching any GraalVM class, giving this test time
        // to read the child's PID and create the collision at the exact path EngineHolder will compute.
        // java.io.tmpdir itself is left untouched (real, writable) so unrelated JVM startup machinery
        // (e.g. Flight Recorder, which writes directly under java.io.tmpdir) is unaffected -- only that
        // one specific path collides. The forked main runs two scripts back to back: if the caught
        // IOException were rethrown instead of logged and swallowed, the static holder would be poisoned
        // for the rest of the JVM's lifetime (JLS class-initialization semantics), and even the second
        // script would fail with NoClassDefFoundError instead of the original exception.
        Path goFile = tempDir.resolve("go-signal");

        ForkedJvm.run(
            List.of("-Djava.io.tmpdir=" + tempDir),
            EngineHolderFallbackForkMain.class,
            EngineHolderFallbackForkMain.SUCCESS_MARKER,
            List.of(goFile.toString()),
            pid -> {
                Files.createFile(tempDir.resolve("kestra-graalvm-resource-cache-" + pid));
                Files.createFile(goFile);
            }
        );
    }

    @Test
    void rejectsSandboxWeakeningOption() {
        RunContext runContext = runContextFactory.of();

        Eval task = Eval.builder()
            .id("unit-test")
            .type(Eval.class.getName())
            .script(Property.ofValue("pass\n"))
            .options(Property.ofValue(Map.of("python.PosixModuleBackend", "native")))
            .build();

        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(exception.getMessage(), containsString("python.PosixModuleBackend"));
    }

    @Test
    void blocksOsSystemDespiteNativeAccess() {
        // allowNativeAccess(true) is required engine-wide for C-extension-backed stdlib modules (ssl,
        // sqlite3, lzma -- see stdlibImports() above), but it does NOT, on its own, unlock OS process
        // creation: GraalPy's default POSIX backend routes os.system through
        // TruffleLanguage.Env#newProcessBuilder, which is gated by the separate allowCreateProcess
        // context flag (kept false in AbstractScript#buildContext). Verified experimentally before this
        // fix: os.system fails with "Process creation is not allowed" even with native access on.
        RunContext runContext = runContextFactory.of();

        Eval task = Eval.builder()
            .id("unit-test")
            .type(Eval.class.getName())
            .script(Property.ofValue("import os\nos.system('echo pwned')\n"))
            .build();

        var exception = assertThrows(PolyglotException.class, () -> task.run(runContext));
        assertThat(exception.getMessage(), containsString("Process creation is not allowed"));
    }

    @Test
    void blocksSubprocessDespiteNativeAccess() throws IOException {
        // Same boundary as blocksOsSystemDespiteNativeAccess() above, exercised through subprocess
        // instead of os.system: subprocess.run() also goes through the emulated POSIX backend's
        // fork_exec, which ultimately hits the same allowCreateProcess-gated Env#newProcessBuilder call
        // and fails, surfaced to the script as a PermissionError.
        // copied into the working dir, otherwise the file system rejects /bin/echo before process creation
        RunContext runContext = runContextFactory.of();
        var echo = Files.copy(Path.of("/bin/echo"), runContext.workingDir().path().resolve("echo"));
        assertThat(echo.toFile().setExecutable(true), is(true));

        Eval task = Eval.builder()
            .id("unit-test")
            .type(Eval.class.getName())
            .script(Property.ofValue("import subprocess\nsubprocess.run(['./echo', 'pwned'], capture_output=True)\n"))
            .build();

        var exception = assertThrows(PolyglotException.class, () -> task.run(runContext));
        assertThat(exception.getMessage(), containsString("not permitted"));
    }

    @Test
    void guestFileAccessInsideWorkingDir() throws Exception {
        RunContext runContext = runContextFactory.of();

        var runOutput = evalOf("""
            import os
            import tempfile
            with open('data.txt', 'w') as f:
                f.write('hello')
            os.makedirs('nested/dir')
            with open('nested/dir/../../data.txt') as f:
                content = f.read()
            with tempfile.NamedTemporaryFile('w', delete=False) as tmp:
                tmp.write('temp')
            files = sorted(os.listdir('.'))
            """, "content", "files").run(runContext);

        assertThat(runOutput.getOutputs().get("content"), is("hello"));
        assertThat(Files.readString(runContext.workingDir().path().resolve("data.txt")), is("hello"));
    }

    @Test
    void guestFileAccessOutsideWorkingDirIsDenied(@TempDir Path outsideDir) throws Exception {
        var secret = Files.writeString(outsideDir.resolve("secret.txt"), "secret");

        assertFileAccessDenied("open('%s').read()".formatted(secret));
        assertFileAccessDenied("open('%s', 'w').write('x')".formatted(outsideDir.resolve("created.txt")));
        assertFileAccessDenied("import os\nos.listdir('%s')".formatted(outsideDir));
        assertFileAccessDenied("open('" + "../".repeat(30) + "etc/hosts').read()");
        // the same error whether the outside path exists or not, so scripts cannot probe the host
        assertFileAccessDenied("import os\nos.stat('%s')".formatted(secret.resolve("x")));
        assertFileAccessDenied("import os\nos.stat('%s')".formatted(outsideDir.resolve("missing/x")));
        assertFileAccessDenied("import os\nos.stat('%s')".formatted(outsideDir.resolve("missing/../secret.txt")));
        assertThat(Files.exists(outsideDir.resolve("created.txt")), is(false));
    }

    @Test
    void guestFileAccessThroughSymbolicLinkOutsideIsDenied(@TempDir Path outsideDir) throws Exception {
        var secret = Files.writeString(outsideDir.resolve("secret.txt"), "secret");
        RunContext runContext = runContextFactory.of();
        Files.createSymbolicLink(runContext.workingDir().path().resolve("link.txt"), secret);
        Files.createSymbolicLink(runContext.workingDir().path().resolve("linkDir"), outsideDir);

        assertFileAccessDenied(runContext, "open('link.txt').read()");
        assertFileAccessDenied(runContext, "open('linkDir/secret.txt').read()");
        // a link target outside the working dir is refused as a cross file system link
        var exception = assertThrows(PolyglotException.class, () -> evalOf("import os\nos.symlink('%s', 'newLink')".formatted(secret)).run(runContext));
        assertThat(exception.getMessage(), either(startsWith("PermissionError")).or(startsWith("OSError")));
        assertThat(Files.exists(runContext.workingDir().path().resolve("newLink"), java.nio.file.LinkOption.NOFOLLOW_LINKS), is(false));
    }

    @Test
    void guestFileAccessOutsideWorkingDirIsDeniedWithModules(@TempDir Path outsideDir) throws Exception {
        // GraalPyResources.contextBuilder() sets its own IOAccess
        var secret = Files.writeString(outsideDir.resolve("secret.txt"), "secret");
        RunContext runContext = runContextFactory.of();

        Eval task = Eval.builder()
            .id("unit-test")
            .type(Eval.class.getName())
            .modules(Property.ofValue(Map.of("hello.py", "def hello():\n  return 'hello'\n")))
            .script(Property.ofValue("import hello\nopen('%s').read()\n".formatted(secret)))
            .build();

        var exception = assertThrows(PolyglotException.class, () -> task.run(runContext));
        assertThat(exception.getMessage(), startsWith("PermissionError"));
    }

    private static final String STDLIB_FILE = """
        import colorsys, os
        stdlib_file = colorsys.__file__
        """;

    @Test
    void internalResourcesAreReadOnly() throws Exception {
        for (var operation : List.of(
            "open(stdlib_file, 'a').write('X = 1\\n')",
            "open(stdlib_file, 'w').write('X = 1\\n')",
            "open(stdlib_file + '.new', 'w').write('X = 1\\n')",
            "os.remove(stdlib_file)",
            "os.rename(stdlib_file, stdlib_file + '.bak')"
        )) {
            var runContext = runContextFactory.of();
            var before = readStdlibFile(runContext);
            var exception = assertThrows(PolyglotException.class, () -> evalOf(STDLIB_FILE + operation + "\n").run(runContext), operation);
            assertThat(operation, exception.getMessage(), either(startsWith("PermissionError")).or(startsWith("OSError")));
            assertThat(operation, readStdlibFile(runContext), is(before));
        }
    }

    @Test
    void relativePathsAfterChdirAreResolvedAgainstWorkingDir() throws Exception {
        var runContext = runContextFactory.of();
        var workingDir = runContext.workingDir().path();
        Files.createDirectories(workingDir.resolve("a/b"));
        // sibling of the working dir: exactly three levels up from a/b
        var secret = Files.writeString(workingDir.resolveSibling("secret-" + UUID.randomUUID() + ".txt"), "secret");
        var chdir = "import os\nos.chdir('a/b')\n";
        try {
            // a short relative path to a real file outside the working dir is denied, for reads and writes
            for (var operation : List.of("open('../../../%s').read()", "open('../../../%s', 'w').write('x')")) {
                var script = chdir + operation.formatted(secret.getFileName()) + "\n";
                var exception = assertThrows(PolyglotException.class, () -> evalOf(script).run(runContext), script);
                assertThat(script, exception.getMessage(), either(startsWith("PermissionError")).or(startsWith("OSError")));
            }
            assertThat(Files.readString(secret), is("secret"));

            // the bundled stdlib stays readable, and read-only, through a relative path computed from the new cwd
            var before = readStdlibFile(runContext);
            var relative = chdir + STDLIB_FILE + "relative = os.path.relpath(stdlib_file)\n";
            var output = evalOf(relative + "content = open(relative).read()\n", "content").run(runContext);
            assertThat(output.getOutputs().get("content"), is(before));

            assertThrows(PolyglotException.class, () -> evalOf(relative + "open(relative, 'a').write('X = 1\\n')\n").run(runContext));
            assertThat(readStdlibFile(runContext), is(before));
        } finally {
            Files.deleteIfExists(secret);
        }
    }

    @Test
    void internalResourcesCannotBeChangedAcrossTasks() throws Exception {
        assertThrows(PolyglotException.class, () -> evalOf(STDLIB_FILE + "open(stdlib_file, 'a').write('\\nINJECTED = 1\\n')\n").run(runContextFactory.of()));

        var output = evalOf("import colorsys\nstate = getattr(colorsys, 'INJECTED', 'clean')\n", "state").run(runContextFactory.of());
        assertThat(output.getOutputs().get("state"), is("clean"));
    }

    // ssl and sqlite3 imports are covered by stdlibImports: a native module can be loaded by only one context per JVM
    @Test
    void internalResourcesStayReadable() throws Exception {
        var output = evalOf("""
            import json, colorsys
            state = json.dumps({'a': colorsys.rgb_to_hsv(0, 0, 0)[0]})
            """, "state").run(runContextFactory.of());
        assertThat(output.getOutputs().get("state"), is("{\"a\": 0.0}"));

        // modules switches the context to GraalPyResources.contextBuilder
        var withModules = Eval.builder()
            .id("unit-test")
            .type(Eval.class.getName())
            .modules(Property.ofValue(Map.of("hello.py", "X = 1\n")))
            .script(Property.ofValue("import hello, json, colorsys\nstate = hello.X\n"))
            .outputs(Property.ofValue(List.of("state")))
            .build()
            .run(runContextFactory.of());
        assertThat(withModules.getOutputs().get("state"), is(1));
    }

    private String readStdlibFile(RunContext runContext) throws Exception {
        var output = evalOf(STDLIB_FILE + "content = open(stdlib_file).read()\n", "content").run(runContext);
        return (String) output.getOutputs().get("content");
    }

    private Eval evalOf(String script, String... outputs) {
        return Eval.builder()
            .id("unit-test")
            .type(Eval.class.getName())
            .script(Property.ofValue(script))
            .outputs(Property.ofValue(List.of(outputs)))
            .build();
    }

    private void assertFileAccessDenied(String script) {
        assertFileAccessDenied(runContextFactory.of(), script);
    }

    private void assertFileAccessDenied(RunContext runContext, String script) {
        var exception = assertThrows(PolyglotException.class, () -> evalOf(script).run(runContext));
        assertThat(exception.getMessage(), startsWith("PermissionError"));
    }

    @Test
    void nativeAccessAllowsRawNativeFfiAsDocumented() throws Exception {
        // Pins down the documented, accepted residual risk of allowNativeAccess(true) (see the @Schema
        // description on Eval and AbstractScript#allowNativeAccess): a script that reaches native code
        // directly via ctypes -- bypassing GraalPy's POSIX/process-creation layer entirely, since
        // ctypes.CDLL(...).system(...) calls libc directly through NFI rather than through
        // TruffleLanguage.Env#newProcessBuilder -- can still run arbitrary OS commands. There is no
        // GraalVM/GraalPy API to prevent this while keeping native access on for ssl/sqlite3/lzma
        // (confirmed: sys.addaudithook is a documented-but-unimplemented no-op in GraalPy 24.2.2, and
        // sys.modules poisoning is trivially undone by guest code). This test exists to make that
        // tradeoff an explicit, visible regression check rather than a silent assumption: if this ever
        // starts failing, GraalVM has changed its native-access model and the @Schema disclosure and this
        // test both need revisiting.
        RunContext runContext = runContextFactory.of();

        Eval task = Eval.builder()
            .id("unit-test")
            .type(Eval.class.getName())
            .script(Property.ofValue("""
                import ctypes
                result = ctypes.CDLL(None).system(b"exit 0")
                """))
            .outputs(Property.ofValue(List.of("result")))
            .build();

        var runOutput = task.run(runContext);
        assertThat(runOutput.getOutputs().get("result"), is(0));
    }
}
