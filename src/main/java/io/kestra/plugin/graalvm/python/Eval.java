package io.kestra.plugin.graalvm.python;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.enums.MonacoLanguages;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.storages.StorageContext;
import io.kestra.plugin.graalvm.AbstractEval;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.FileSystem;
import io.kestra.plugin.graalvm.WorkingDirFileSystem;
import org.graalvm.python.embedding.GraalPyResources;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static io.kestra.core.utils.Rethrow.throwBiConsumer;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Execute inline Python with GraalVM",
    description = "Runs inline Python inside the task JVM via GraalVM. Access `runContext`, `logger`, and rendered variables from the bindings; declare names in `outputs` to return them. File access through Python (`open`, `os`, `pathlib`, `tempfile`) is limited to the task working directory (the bundled standard library is read-only for scripts), except for native modules (see below); host class access is restricted to `java.*` and `io.kestra.core.models.*`, minus classes that reach files, processes, reflection, or the network. To upload a file, read its bytes and pass them to `runContext.storage().putFile(java.io.ByteArrayInputStream(data), 'name')`. Optional `modules` preload Python files from content or `kestra://` URIs onto the module path. Supports C-extension-backed stdlib modules such as `ssl`, `sqlite3`, and `lzma`, which requires enabling native access at the GraalVM engine level. Standard OS process APIs (`os.system`, `subprocess`) stay blocked, but this grant cannot be scoped down further: a script that reaches native code directly (e.g. `ctypes`) can still execute arbitrary OS commands, read or write any file the worker can access, or manipulate process memory. Native modules also open files themselves, outside the working directory restriction: `sqlite3.connect()` can read or create a database file anywhere the worker can. Unlike Kestra's `Script`/`Shell` tasks, which typically run in an isolated container or process via a `TaskRunner`, this code runs inline in the worker JVM process itself, so it has direct access to the worker process's own memory, file descriptors, and any secrets or other task state resident in that JVM. Only run scripts from users already trusted with the flow's credentials and infrastructure, as with any script task."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "Read an uploaded CSV file with Python.",
            code = """
                id: read_uploaded_csv
                namespace: company.team

                inputs:
                  - id: csv_file
                    type: FILE

                tasks:
                  - id: process_csv
                    type: io.kestra.plugin.graalvm.python.Eval
                    outputs:
                      - row_count
                    script: |
                      import csv
                      with open('{{ inputs.csv_file }}', 'r') as csvfile:
                          row_count = sum(1 for row in csv.DictReader(csvfile))
                      logger.info('Read {} rows'.format(row_count))
                      {"row_count": row_count}
                """
        ),
        @Example(
            full = true,
            title = "Parse a downloaded JSON and update one of its fields.",
            code = """
                id: parse_json_data
                namespace: company.team

                tasks:
                  - id: download
                    type: io.kestra.plugin.core.http.Download
                    uri: http://xkcd.com/info.0.json

                  - id: graal
                    type: io.kestra.plugin.graalvm.python.Eval
                    outputs:
                      - data
                    script: |
                      data = {{ read(outputs.download.uri )}}
                      data["next_month"] = int(data["month"]) + 1
                """
        ),
        @Example(
            full = true,
            title = "Execute a Python script using the GraalVM scripting engine.",
            code = """
                    id: evalPython
                    namespace: company.team

                    tasks:
                      - id: evalPython
                        type: io.kestra.plugin.graalvm.python.Eval
                        outputs:
                          - out
                          - map
                        script: |
                            import java
                            import java.io.ByteArrayInputStream as ByteArrayInputStream
                            # types other than one coming from the Java SDK must be defined this way
                            Counter = java.type("io.kestra.core.models.executions.metrics.Counter")
                            logger.info('Task started')
                            runContext.metric(Counter.of('total', 666, 'name', 'bla'))
                            map = {'test': 'here'}
                            content = ByteArrayInputStream('Hello World'.encode('utf-8'))
                            out = runContext.storage().putFile(content, 'out.txt')
                            {"map": map, "out": out}"""
        ),
        @Example(
            full = true,
            title = "Define a Python module, then execute a script that imports this module using the GraalVM scripting engine.",
            code = """
                    id: evalPython
                    namespace: company.team

                    tasks:
                      - id: evalPython
                        type: io.kestra.plugin.graalvm.python.Eval
                        modules:
                          hello.py: |
                            def hello(name):
                              return("Hello " + name)
                        script: |
                          import hello
                          logger.info(hello.hello("Kestra"))"""
        )
    },
    metrics = {
      @Metric(
          name = "records",
          type = Counter.TYPE,
          unit = "count",
          description = "Tracks a user defined numeric value emitted from the Python script, such as the number of processed records or computed results."
      )
    }
)
public class Eval extends AbstractEval {
    private static final Path MODULE_PATH = Path.of("__kestra_python");

    @Schema(
        title = "Inline Python modules to preload",
        description = "Map of filename to module content or a `kestra://` URI; files are written to a temporary module path before execution"
    )
    @PluginProperty(group = "advanced")
    private Property<Map<String, String>> modules;

    @Schema(
        title = "Python script body to execute",
        description = "Template-rendered Python source. Python file operations can open `kestra://` URIs, including uploaded FILE inputs. Access stages a local copy in the task working directory; repeated accesses in the same task use that copy, and local changes do not update internal storage. URI strings and comments are unchanged. Staged files are removed with the task working directory."
    )
    @PluginProperty(language = MonacoLanguages.PYTHON)
    @Override
    public Property<String> getScript() {
        return super.getScript();
    }

    @Override
    public Output run(RunContext runContext) throws Exception {
        if (modules != null) {
            Path modulePath = runContext.workingDir().resolve(MODULE_PATH).resolve("src");
            Files.createDirectories(modulePath);
            final Path normalizedModulePath = modulePath.normalize();
            runContext.render(modules).asMap(String.class, String.class).forEach(throwBiConsumer((k, v) -> {
                // Prevent path traversal: reject absolute paths and keys containing separators
                if (k.contains("/") || k.contains("\\") || k.startsWith(".")) {
                    throw new IllegalArgumentException("Module filename must not contain path separators or start with '.': " + k);
                }
                Path moduleFile = modulePath.resolve(k);
                // Verify the resolved path stays within the module directory
                if (!moduleFile.normalize().startsWith(normalizedModulePath)) {
                    throw new IllegalArgumentException("Module filename escapes module directory: " + k);
                }
                byte[] content;
                if (v.startsWith(StorageContext.KESTRA_PROTOCOL)) {
                    content = runContext.storage().getFile(URI.create(v)).readAllBytes();
                } else {
                    content = v.getBytes();
                }
                Files.write(moduleFile, content);
            }));
        }

        return this.run(runContext, "python");
    }

    @Override
    protected FileSystem fileSystem(RunContext runContext) throws IOException {
        return WorkingDirFileSystem.create(runContext.workingDir().path(), runContext.storage());
    }

    @Override
    protected Context.Builder contextBuilder(RunContext runContext) {
        if (modules == null) {
            return super.contextBuilder(runContext);
        } else {
            return GraalPyResources.contextBuilder(runContext.workingDir().resolve(MODULE_PATH));
        }
    }

    @Override
    protected boolean allowNativeAccess() {
        return true;
    }
}
