package io.kestra.plugin.graalvm;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.graalvm.polyglot.*;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.polyglot.io.FileSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import io.kestra.core.models.annotations.PluginProperty;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
abstract class AbstractScript extends Task {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractScript.class);

    @Schema(
        title = "Script body to execute",
        description = "Template-rendered source code run by GraalVM in the selected language; flow variables are resolved before execution"
    )
    @NotNull
    @PluginProperty(group = "main")
    protected Property<String> script;

    @Schema(
        title = "Advanced GraalVM context options",
        description = """
            Maps directly to GraalVM's `Context.Builder#options`: one entry per option key/value, for example `python.WarnOptions` or `js.ecmascript-version`.
            Keys that would weaken the sandbox already enforced by this task are rejected at execution time: `python.PosixModuleBackend`, any key related to host access or class loading, and any key prefixed with `engine.` or `sandbox.`.
            """
    )
    @PluginProperty(group = "advanced")
    protected Property<Map<String, String>> options;

    // Keys that would let a script weaken the sandbox already enforced by buildContext() below.
    private static final Set<String> DENIED_OPTION_KEYS = Set.of("python.PosixModuleBackend");
    private static final List<String> DENIED_OPTION_PREFIXES = List.of("engine.", "sandbox.");
    private static final List<String> DENIED_OPTION_KEYWORDS = List.of(
        "hostaccess", "hostclasslookup", "hostclassloading", "hostlookup", "classloader", "classloading"
    );

    // on top of the HostAccess denyAccess rules in buildContext()
    private static final Set<String> DENIED_HOST_CLASSES = Set.of(
        "java.lang.Runtime", "java.lang.System", "java.lang.Class", "java.lang.ClassLoader",
        // these open files directly on the host, bypassing WorkingDirFileSystem
        "java.io.File", "java.io.FileInputStream", "java.io.FileOutputStream", "java.io.FileReader",
        "java.io.FileWriter", "java.io.RandomAccessFile", "java.io.PrintStream", "java.io.PrintWriter",
        "java.util.Formatter", "java.util.zip.ZipFile", "java.util.jar.JarFile",
        // loads code from a module layer built from a jar in the working dir
        "java.util.ServiceLoader",
        // deserialization resolves classes without the lookup filter, so gadget chains on the classpath could run any code
        "java.io.ObjectInputStream",
        // runs JDK tools such as jar and javac in the worker, which read and write any file on the host
        "java.util.spi.ToolProvider"
    );
    private static final List<String> DENIED_HOST_PACKAGES = List.of(
        "java.lang.Process",
        "java.lang.reflect.", "java.lang.invoke.", "java.net.", "java.rmi.", "javax.script.", "sun.", "com.sun.",
        // file access, heap dumps (management) and native calls (foreign)
        "java.nio.file.", "java.nio.channels.", "java.util.logging.", "java.util.prefs.",
        "java.lang.management.", "java.lang.foreign.",
        // reflection inside Java (XMLDecoder, Statement) and file-based JDBC drivers
        "java.beans.", "java.sql.",
        // Module and ModuleLayer, and module finders that can load a jar from the working dir
        "java.lang.Module", "java.lang.module.",
        // Toolkit, Font and ImageIO-backed classes load files by name directly on the host
        "java.awt.",
        // these classes (PluginUtilsService, ScriptService, TaskCommands) access host files directly, outside WorkingDirFileSystem
        "io.kestra.core.models.tasks.runners."
    );

    protected Context buildContext(RunContext runContext, OutputStream out, OutputStream err) throws IllegalVariableEvaluationException, IOException {
        var builder = contextBuilder(runContext)
            .engine(getEngine())
            // allow host access with a curated default
            .allowHostAccess(HostAccess
                    .newBuilder(HostAccess.EXPLICIT)
                    .allowArrayAccess(true).allowListAccess(true).allowBufferAccess(true).allowIterableAccess(true).allowIteratorAccess(true).allowMapAccess(true).allowPublicAccess(true)
                    // Deny method invocation on dangerous types even when an instance is obtained
                    // indirectly (e.g. via Object.getClass()). This closes reflection-based bypasses
                    // of allowHostClassLookup such as `x.getClass().getClassLoader().loadClass(...)`.
                    .denyAccess(Class.class)
                    .denyAccess(ClassLoader.class)
                    .denyAccess(java.lang.reflect.AccessibleObject.class) // Method, Field, Constructor
                    .denyAccess(java.lang.reflect.Executable.class)
                    .denyAccess(Runtime.class)
                    .denyAccess(ProcessBuilder.class)
                    .denyAccess(Process.class)
                    .denyAccess(System.class)
                    // paths returned by Kestra APIs could otherwise be resolved to any file
                    .denyAccess(java.io.File.class)
                    .denyAccess(java.nio.file.Path.class)
                    .denyAccess(java.nio.file.FileSystem.class)
                    .denyAccess(java.nio.file.spi.FileSystemProvider.class)
                    // a URI from a Kestra API could otherwise open any file: URL
                    .denyAccess(java.net.URL.class)
                    .denyAccess(java.net.URLConnection.class)
                    // an instance obtained without a class lookup must not deserialize either
                    .denyAccess(java.io.ObjectInputStream.class)
                    .build()
            )
            // no Java.addToClasspath(): a jar written to the working dir would run as unrestricted host code
            .allowHostClassLoading(false)
            // restrict loading class to java.* and io.kestra.core.models.* but deny dangerous classes and packages
            .allowHostClassLookup(AbstractScript::isHostClassLookupAllowed)
            // log to the run context logger
            .logHandler(new SLF4JJULHandler(runContext.logger()))
            // needed for Ruby
            .allowPolyglotAccess(PolyglotAccess.ALL)
            // needed for Ruby
            .allowCreateThread(true)
            // needed by Python C extensions such as ssl, sqlite3, and lzma; see allowNativeAccess()
            // below for why this engine-wide grant cannot be scoped down further
            .allowNativeAccess(allowNativeAccess())
            // Explicit even though it is GraalVM's default: process creation must stay off regardless of
            // allowNativeAccess(). Verified experimentally (see python.EvalTest#blocksOsSystemDespiteNativeAccess
            // and #blocksSubprocessDespiteNativeAccess) that GraalPy's default ("java"/emulated) POSIX
            // backend routes os.system/subprocess through TruffleLanguage.Env#newProcessBuilder, which this
            // flag -- not allowNativeAccess -- gates: with it off, both fail with
            // SecurityException/PermissionError even when native access is on.
            .allowCreateProcess(false)
            // also overrides the IOAccess set by GraalPyResources.contextBuilder()
            .allowIO(IOAccess.newBuilder()
                .fileSystem(fileSystem(runContext))
                .allowHostSocketAccess(true)
                .build()
            )
            .currentWorkingDirectory(runContext.workingDir().path())
            .out(out)
            .err(err);

        runContext.render(this.options).asMap(String.class, String.class).forEach((key, value) -> {
            validateOptionKey(key);
            builder.option(key, value);
        });

        return builder.build();
    }

    protected FileSystem fileSystem(RunContext runContext) throws IOException {
        return WorkingDirFileSystem.create(runContext.workingDir().path());
    }

    private static boolean isHostClassLookupAllowed(String name) {
        if (DENIED_HOST_CLASSES.contains(name) || DENIED_HOST_PACKAGES.stream().anyMatch(name::startsWith)) {
            return false;
        }
        return name.startsWith("java.") || name.startsWith("io.kestra.core.models");
    }

    private static void validateOptionKey(String key) {
        if (DENIED_OPTION_KEYS.contains(key)) {
            throw new IllegalArgumentException("Context option '" + key + "' is not allowed: it lets the guest language bypass the sandboxed POSIX backend enforced by this task. Remove it from `options`; this restriction is permanent and cannot be overridden.");
        }
        if (DENIED_OPTION_PREFIXES.stream().anyMatch(key::startsWith)) {
            throw new IllegalArgumentException("Context option '" + key + "' is not allowed: options prefixed with 'engine.' or 'sandbox.' can weaken the sandboxing enforced by this task. Remove it from `options`; engine- and sandbox-level configuration is not exposed to task scripts.");
        }
        var lowerKey = key.toLowerCase();
        if (DENIED_OPTION_KEYWORDS.stream().anyMatch(lowerKey::contains)) {
            throw new IllegalArgumentException("Context option '" + key + "' is not allowed: it would override the host-access or class-loading restrictions already enforced by this task. Remove it from `options`; these restrictions are permanent and cannot be overridden.");
        }
    }

    protected Value getBindings(Context context, String languageId) {
        return context.getBindings(languageId);
    }

    /**
     * Whether this language needs GraalVM's engine-wide native-access grant, e.g. Python's C-extension
     * stdlib modules (ssl, sqlite3, lzma).
     * <p>
     * GraalVM's {@code allowNativeAccess} is an all-or-nothing switch: there is no finer-grained polyglot
     * API to grant native access to GraalVM's own bundled C-extension loader while denying it to
     * guest-script code that reaches for raw native FFI directly (e.g. a Python script calling
     * {@code ctypes.CDLL(None).system(...)}). Neither {@code SandboxPolicy} (a coarse
     * TRUSTED/CONSTRAINED/ISOLATED/UNTRUSTED build-time check, not a runtime restriction), GraalPy's
     * {@code python.NativeModules} option (still refuses to run any C extension at all when native access
     * is disallowed), nor Python-level mitigations (poisoning {@code sys.modules} entries is trivially
     * undone by guest code with `del sys.modules[...]`; GraalPy 24.2.2's {@code sys.addaudithook} is a
     * documented-but-unimplemented no-op -- verified by decompiling {@code SysModuleBuiltins} and
     * confirming both {@code AuditNode.doAudit} and {@code SysAuditHookNode.doAudit} return immediately
     * without recording or invoking any hook) offer a way to scope this down further. Languages that
     * enable this therefore run scripts with a trust requirement at least as strict as Kestra assumes for
     * {@code Script}/{@code Shell} tasks: not for arbitrary, adversarial, untrusted input as code. Unlike
     * those tasks, which typically execute in an isolated container or process via a {@code TaskRunner},
     * this code runs inline in the worker JVM process itself, so native code reached this way has direct
     * access to the worker's own memory, file descriptors, and any secrets or other task state resident in
     * that JVM -- a materially larger blast radius than an isolated container escape. See the
     * {@code @Schema} description on the affected tasks for the user-facing disclosure of this tradeoff.
     * <p>
     * What native access does <em>not</em> unlock on its own is OS process creation: GraalPy's default
     * ("java"/emulated) POSIX backend routes {@code os.system}/{@code subprocess} through
     * {@code TruffleLanguage.Env#newProcessBuilder}, which is gated by the separate
     * {@code allowCreateProcess} context flag (kept {@code false} in {@link #buildContext}, verified
     * experimentally to still block both even with native access on). Only guest code that reaches native
     * libc functions directly (ctypes) bypasses that control, since it never goes through Truffle's
     * process API at all.
     */
    protected boolean allowNativeAccess() {
        return false;
    }

    protected Context.Builder contextBuilder(RunContext runContext) {
        return Context.newBuilder();
    }

    // initialization-on-demand holder idiom
    private static class EngineHolder {
        static final Engine INSTANCE = createEngine();

        private static Engine createEngine() {
            // GraalPy/TruffleRuby extract their stdlib to this cache directory on first use. If it's
            // unset, the default is a user home directory (~/.cache/org.graalvm.polyglot) that may be
            // unwritable, wiped, or corrupted in rootless / distributed deployments (issue #40). Default
            // it to a writable path under java.io.tmpdir, but never override an operator-set value.
            if (System.getProperty("polyglot.engine.userResourceCache") == null
                    && System.getProperty("polyglot.engine.resourcePath") == null) {
                // Unique per-process directory name (PID) so no other local user/process on a shared
                // host can predict, pre-create, or plant tampered resources at this path before this JVM
                // does (CWE-377): Files.createDirectories() succeeds on an already-existing directory, so
                // a fixed shared name would let an attacker-owned or -poisoned dir be silently adopted.
                var cacheDir = Path.of(
                    System.getProperty("java.io.tmpdir"),
                    "kestra-graalvm-resource-cache-" + ProcessHandle.current().pid()
                );
                try {
                    var fileStore = Files.getFileStore(cacheDir.getParent());
                    if (fileStore.supportsFileAttributeView(PosixFileAttributeView.class)) {
                        Files.createDirectories(cacheDir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                    } else {
                        Files.createDirectories(cacheDir);
                    }
                    System.setProperty("polyglot.engine.userResourceCache", cacheDir.toString());
                } catch (IOException e) {
                    // Do NOT throw from a static field initializer: per JLS class-initialization
                    // semantics, an exception here would poison this holder class for the entire JVM
                    // lifetime, turning every subsequent call into a NoClassDefFoundError instead of the
                    // original exception -- a transient tmpdir permission issue on worker boot would
                    // become a permanent outage for every GraalVM script task until process restart.
                    // Fall back to GraalVM's own default resource-cache resolution instead: issue #40 may
                    // resurface, but only in this specific failure mode, which is strictly better than a
                    // total, unrecoverable outage.
                    LOG.warn("Unable to create a writable GraalVM resource cache directory at '{}'; falling back to GraalVM's default resource cache resolution, which may be unwritable in rootless or distributed deployments (see issue #40)", cacheDir, e);
                }
            }

            // Ruby looks up its home by real path, which WorkingDirFileSystem only lets through when it matches these
            useRealPath("polyglot.engine.userResourceCache");
            useRealPath("polyglot.engine.resourcePath");

            return Engine.create();
        }

        private static void useRealPath(String property) {
            var value = System.getProperty(property);
            if (value == null) {
                return;
            }
            try {
                // the directory may not exist yet, so resolve its deepest existing parent
                var path = Path.of(value).toAbsolutePath();
                var existing = path;
                while (existing != null && !Files.exists(existing)) {
                    existing = existing.getParent();
                }
                if (existing == null) {
                    return;
                }
                System.setProperty(property, existing.toRealPath().resolve(existing.relativize(path)).toString());
            } catch (IOException | InvalidPathException e) {
                // never throw from the static initializer, see createEngine()
                LOG.warn("Unable to resolve the real path of '{}' ({}); Ruby scripts may fail to load their standard library", property, value, e);
            }
        }
    }

    private Engine getEngine() {
        return EngineHolder.INSTANCE;
    }

    protected Source generateSource(String languageId, RunContext runContext) throws IllegalVariableEvaluationException {
        var rendered = runContext.render(this.script).as(String.class).orElseThrow();
        return Source.create(languageId, rendered);
    }
}
