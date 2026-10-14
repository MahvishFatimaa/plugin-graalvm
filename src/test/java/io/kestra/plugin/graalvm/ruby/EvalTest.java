package io.kestra.plugin.graalvm.ruby;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.graalvm.ForkedJvm;
import jakarta.inject.Inject;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.Matchers.startsWith;
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
            .script(Property.ofExpression("return {uri: '{{ inputs.uri }}'}"))
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

    @Test
    void runFunction() throws Exception {
        RunContext runContext = runContextFactory.of();

        Eval task = Eval.builder()
            .id("unit-test")
            .type(Eval.class.getName())
            .script(Property.ofValue(
                """
                      Counter = Java.type('io.kestra.core.models.executions.metrics.Counter')
                      ByteArrayInputStream = Java.type('java.io.ByteArrayInputStream')
                      StandardCharsets = Java.type('java.nio.charset.StandardCharsets')
                      # all variables must be imported before use
                      logger = Polyglot.import('logger')
                      runContext = Polyglot.import('runContext')
                      logger.info('Task started')
                      runContext.metric(Counter.of('total', 666, 'name', 'bla'))
                      map = {test: 'here'}
                      bytes = StandardCharsets.UTF_8.encode('Hello World')
                      content = ByteArrayInputStream.new(bytes.array(), 0, bytes.limit())
                      out = runContext.storage().putFile(content, 'out.txt')
                      return {map: map, out: out}
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
    void startsWithResourceCacheBehindSymbolicLink(@TempDir Path tempDir) throws Exception {
        // Ruby looks up its home by real path, so a symlinked cache path must still be allowed
        var realDir = Files.createDirectory(tempDir.resolve("real"));
        var link = Files.createSymbolicLink(tempDir.resolve("link"), realDir);

        ForkedJvm.run(
            List.of("-Dpolyglot.engine.userResourceCache=" + link.resolve("cache")),
            RubyStartupForkMain.class,
            RubyStartupForkMain.SUCCESS_MARKER
        );
    }

    @Test
    void rubyFileApisNeedNativeAccess(@TempDir Path outsideDir) throws Exception {
        // Ruby File/Dir use native calls that bypass WorkingDirFileSystem, so they must stay unavailable
        var secret = Files.writeString(outsideDir.resolve("secret.txt"), "secret");
        RunContext runContext = runContextFactory.of();
        Files.writeString(runContext.workingDir().path().resolve("inside.txt"), "inside");

        assertDenied(runContext, "File.read('inside.txt')", is("native access is not allowed"));
        assertDenied(runContext, "File.read('%s')".formatted(secret), is("native access is not allowed"));
    }

    @Test
    void loadingCodeFromOutsideWorkingDirIsDenied(@TempDir Path outsideDir) throws Exception {
        var evil = Files.writeString(outsideDir.resolve("evil.rb"), "raise 'pwned'\n");
        RunContext runContext = runContextFactory.of();
        Files.createSymbolicLink(runContext.workingDir().path().resolve("link.rb"), evil);

        assertDenied(runContext, "load '%s'".formatted(evil), containsString("only access files inside the task working directory"));
        assertDenied(runContext, "load '%s'".formatted(runContext.workingDir().path().resolve("link.rb")), containsString("only access files inside the task working directory"));
    }

    @Test
    void fileWritesAreRefusedWithoutNativeAccess() throws Exception {
        // Ruby cannot reach the stdlib files: File APIs need native access, which is not granted
        assertDenied(runContextFactory.of(), "File.write($LOAD_PATH.last + '/json.rb', 'x')", is("native access is not allowed"));
    }

    private void assertDenied(RunContext runContext, String script, org.hamcrest.Matcher<String> message) {
        Eval task = Eval.builder()
            .id("unit-test")
            .type(Eval.class.getName())
            .script(Property.ofValue(script))
            .build();

        var exception = assertThrows(PolyglotException.class, () -> task.run(runContext));
        assertThat(exception.getMessage(), message);
    }
}
