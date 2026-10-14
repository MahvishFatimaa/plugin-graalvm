package io.kestra.plugin.graalvm;

import org.graalvm.polyglot.io.FileSystem;
import io.kestra.core.storages.Storage;

import java.io.IOException;
import java.io.FileNotFoundException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.Channels;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HashMap;
import java.util.UUID;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

// Restricts guest file access to the task working directory, resolving symlinks before checking paths.
// Guest code cannot create symlinks, so it cannot swap one in between the check and the file operation.
public final class WorkingDirFileSystem implements FileSystem {
    private static final String TEMP_DIRECTORY_NAME = ".tmp";
    // same as Linux MAXSYMLINKS
    private static final int MAX_SYMBOLIC_LINKS = 40;

    private final FileSystem delegate = FileSystem.newDefaultFileSystem();
    private final Path root;
    private final Storage storage;
    private final Map<URI, Path> storagePaths = new HashMap<>();
    private final Map<Path, URI> pendingDownloads = new HashMap<>();
    // paths visited to resolve the working dir as given, which may go through host symlinks such as
    // /var -> /private/var on macOS; chosen by the host, not the script, so probing them reveals nothing
    private final Set<Path> pathsToRoot = new HashSet<>();
    private volatile Path currentWorkingDirectory;

    WorkingDirFileSystem(Path workingDirectory) throws IOException {
        this(workingDirectory, null);
    }

    private WorkingDirFileSystem(Path workingDirectory, Storage storage) throws IOException {
        this.storage = storage;
        this.root = workingDirectory.toRealPath();
        for (var ancestor = root; ancestor != null; ancestor = ancestor.getParent()) {
            pathsToRoot.add(ancestor);
        }
        walk(workingDirectory, workingDirectory.toAbsolutePath(), true, pathsToRoot::add);
        this.currentWorkingDirectory = this.root;
    }

    // The Python and Ruby stdlib live in the engine-wide resource cache shared by every task on the worker,
    // so scripts can read it but never write to it. Paths outside the working dir and the paths leading to it
    // go to a read-only view of the internal resources (anything else is denied by GraalVM).
    public static FileSystem create(Path workingDirectory) throws IOException {
        return create(workingDirectory, null);
    }

    /** Creates a task-local filesystem that stages internal-storage URIs when accessed. */
    public static FileSystem create(Path workingDirectory, Storage storage) throws IOException {
        var workingDir = new WorkingDirFileSystem(workingDirectory, storage);
        var internalResources = new ReadOnlyInternalResources(
            FileSystem.newReadOnlyFileSystem(FileSystem.allowInternalResourceAccess(FileSystem.newDenyIOFileSystem())),
            workingDir
        );
        return FileSystem.newCompositeFileSystem(
            workingDir,
            FileSystem.Selector.of(internalResources, path -> !workingDir.isAccessible(path))
        );
    }

    // Whether the working dir file system should handle the path (and decide to allow or deny it); false means the
    // path is outside of it and may only be an internal resource. Follows symlinks like realPath, so a working dir
    // reached through a host symlink matches, and never touches the host for a name outside the working dir.
    boolean isAccessible(Path path) {
        var absolute = toAbsolutePath(path);
        var normalized = absolute.normalize();
        // lexically inside: symlinks, if any, are checked by the operation itself
        if (normalized.startsWith(root) || pathsToRoot.contains(normalized)) {
            return true;
        }
        try {
            var resolved = walk(path, absolute, false, candidate -> {
                if (!candidate.startsWith(root) && !pathsToRoot.contains(candidate)) {
                    throw deny(path);
                }
            });
            return resolved.startsWith(root) || pathsToRoot.contains(resolved);
        } catch (SecurityException e) {
            return false;
        } catch (IOException e) {
            // let the working dir file system report the error itself
            return true;
        }
    }

    @Override
    public Path parsePath(URI uri) {
        return storage != null && "kestra".equals(uri.getScheme()) ? storagePath(uri) : delegate.parsePath(uri);
    }

    @Override
    public Path parsePath(String path) {
        return storage != null && path.startsWith("kestra://") ? storagePath(URI.create(path)) : delegate.parsePath(path);
    }

    private synchronized Path storagePath(URI uri) {
        return storagePaths.computeIfAbsent(uri, key -> {
            var path = root.resolve(UUID.randomUUID() + ".tmp");
            pendingDownloads.put(path, key);
            return path;
        });
    }

    private synchronized void stageStorageFile(Path path) throws IOException {
        var uri = pendingDownloads.get(path);
        if (uri == null) {
            return;
        }
        boolean created = false;
        try (var source = storage.getFile(uri)) {
            try (var output = Channels.newOutputStream(delegate.newByteChannel(path,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)))) {
                created = true;
                source.transferTo(output);
            }
        } catch (IOException | RuntimeException e) {
            if (created) {
                try {
                    delegate.delete(path);
                } catch (IOException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
            }
            if (e instanceof FileNotFoundException) {
                throw new NoSuchFileException(uri.toString(), null, e.getMessage());
            }
            throw e;
        }
        pendingDownloads.remove(path);
    }

    @Override
    public void checkAccess(Path path, Set<? extends AccessMode> modes, LinkOption... linkOptions) throws IOException {
        delegate.checkAccess(resolve(path, linkOptions), modes, linkOptions);
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        delegate.createDirectory(resolveNoFollow(dir), attrs);
    }

    @Override
    public void delete(Path path) throws IOException {
        delegate.delete(resolveNoFollow(path));
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        var resolved = options.contains(LinkOption.NOFOLLOW_LINKS) ? resolveNoFollow(path) : resolveFollow(path);
        return delegate.newByteChannel(resolved, options, attrs);
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
        return delegate.newDirectoryStream(resolveFollow(dir), filter);
    }

    @Override
    public Path toAbsolutePath(Path path) {
        return path.isAbsolute() ? path : currentWorkingDirectory.resolve(path);
    }

    @Override
    public Path toRealPath(Path path, LinkOption... linkOptions) throws IOException {
        return delegate.toRealPath(resolve(path, linkOptions), linkOptions);
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        return delegate.readAttributes(resolve(path, options), attributes, options);
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
        delegate.setAttribute(resolve(path, options), attribute, value, options);
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        boolean noFollow = Arrays.asList(options).contains(LinkOption.NOFOLLOW_LINKS);
        Path resolvedSource;
        if (noFollow) {
            resolvedSource = resolveNoFollow(source);
            denyIfSymbolicLink(source, resolvedSource);
        } else {
            resolvedSource = resolveFollow(source);
        }
        delegate.copy(resolvedSource, resolveNoFollow(target), options);
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        var resolvedSource = resolveNoFollow(source);
        denyIfContainsSymbolicLink(source, resolvedSource);
        delegate.move(resolvedSource, resolveNoFollow(target), options);
    }

    @Override
    public void createLink(Path link, Path existing) throws IOException {
        delegate.createLink(resolveNoFollow(link), resolveFollow(existing));
    }

    @Override
    public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attrs) {
        throw new SecurityException("Creating symbolic links is not allowed in GraalVM scripts: '" + link + "' -> '" + target + "'. Write or copy the file into the task working directory instead.");
    }

    @Override
    public Path readSymbolicLink(Path link) throws IOException {
        return delegate.readSymbolicLink(resolveNoFollow(link));
    }

    @Override
    public void setCurrentWorkingDirectory(Path currentWorkingDirectory) {
        if (!currentWorkingDirectory.isAbsolute()) {
            throw new IllegalArgumentException("The current working directory must be an absolute path: " + currentWorkingDirectory);
        }
        try {
            this.currentWorkingDirectory = resolveFollow(currentWorkingDirectory);
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to set the current working directory to '" + currentWorkingDirectory + "'", e);
        }
    }

    @Override
    public String getSeparator() {
        return delegate.getSeparator();
    }

    @Override
    public String getPathSeparator() {
        return delegate.getPathSeparator();
    }

    @Override
    public String getMimeType(Path path) {
        try {
            return delegate.getMimeType(resolveFollow(path));
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    @Override
    public Charset getEncoding(Path path) {
        try {
            return delegate.getEncoding(resolveFollow(path));
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    @Override
    public Path getTempDirectory() {
        var tempDirectory = root.resolve(TEMP_DIRECTORY_NAME);
        try {
            Files.createDirectories(tempDirectory);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create the temporary directory '" + tempDirectory + "' inside the task working directory", e);
        }
        return tempDirectory;
    }

    @Override
    public boolean isSameFile(Path path1, Path path2, LinkOption... options) throws IOException {
        return delegate.isSameFile(resolve(path1, options), resolve(path2, options), options);
    }

    private Path resolve(Path path, LinkOption... linkOptions) throws IOException {
        return Arrays.asList(linkOptions).contains(LinkOption.NOFOLLOW_LINKS) ? resolveNoFollow(path) : resolveFollow(path);
    }

    private Path resolveFollow(Path path) throws IOException {
        return realPath(path, true);
    }

    private Path resolveNoFollow(Path path) throws IOException {
        return realPath(path, false);
    }

    // Resolves the path one name at a time like the OS, following symlinks (including dangling ones, which the
    // OS follows on create). A name outside the working dir and the paths to it is denied before the host is
    // touched, so errors from the host never reveal what exists outside the working directory.
    private Path realPath(Path original, boolean followFinalLink) throws IOException {
        var resolved = walk(original, toAbsolutePath(original), followFinalLink, candidate -> {
            if (!candidate.startsWith(root) && !pathsToRoot.contains(candidate)) {
                throw deny(original);
            }
        });
        if (!resolved.startsWith(root)) {
            throw deny(original);
        }
        if (storage != null) {
            stageStorageFile(resolved);
        }
        return resolved;
    }

    // visit is called on each name before the host is touched for it
    private static Path walk(Path original, Path absolute, boolean followFinalLink, Consumer<Path> visit) throws IOException {
        var pending = new ArrayDeque<Path>();
        absolute.forEach(pending::add);
        var current = absolute.getRoot();
        var currentExists = true;
        var linksFollowed = 0;

        while (!pending.isEmpty()) {
            var name = pending.removeFirst().toString();
            if (name.equals(".")) {
                continue;
            }
            if (name.equals("..")) {
                // like the OS, never go back up through a missing directory
                if (!currentExists) {
                    throw new NoSuchFileException(original.toString());
                }
                // current never contains a symlink, so its parent is the real parent
                current = current.getParent() == null ? current : current.getParent();
                continue;
            }

            var candidate = current.resolve(name);
            visit.accept(candidate);
            current = candidate;
            if (!currentExists) {
                continue;
            }

            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException e) {
                currentExists = false;
                continue;
            }
            if (attributes.isSymbolicLink() && (followFinalLink || !pending.isEmpty())) {
                if (++linksFollowed > MAX_SYMBOLIC_LINKS) {
                    throw new FileSystemException(original.toString(), null, "Too many levels of symbolic links");
                }
                var target = Files.readSymbolicLink(candidate);
                var targetNames = new ArrayList<Path>();
                target.forEach(targetNames::add);
                for (var i = targetNames.size() - 1; i >= 0; i--) {
                    pending.addFirst(targetNames.get(i));
                }
                current = target.isAbsolute() ? target.getRoot() : candidate.getParent();
            } else if (!attributes.isDirectory() && !pending.isEmpty()) {
                throw new NotDirectoryException(original.toString());
            }
        }
        return current;
    }

    private void denyIfSymbolicLink(Path original, Path resolved) {
        if (Files.isSymbolicLink(resolved)) {
            throw new SecurityException("Moving or copying the symbolic link '" + original + "' is not allowed in GraalVM scripts. Copy the file it points to instead.");
        }
    }

    private void denyIfContainsSymbolicLink(Path original, Path resolved) throws IOException {
        if (!Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) {
            denyIfSymbolicLink(original, resolved);
            return;
        }
        try (var paths = Files.walk(resolved)) {
            if (paths.anyMatch(Files::isSymbolicLink)) {
                throw new SecurityException("Moving '" + original + "' is not allowed in GraalVM scripts because it contains a symbolic link.");
            }
        }
    }

    private SecurityException deny(Path path) {
        return new SecurityException("Access to '" + path + "' is denied: GraalVM scripts can only access files inside the task working directory '" + root + "'.");
    }

    // Replaces GraalVM's opaque denial with an actionable message.
    private record ReadOnlyInternalResources(FileSystem delegate, WorkingDirFileSystem workingDir) implements FileSystem {
        private interface IOCall<T> {
            T call() throws IOException;
        }

        // resolve against the working dir cwd used for routing, not the delegate's
        private Path abs(Path path) {
            return workingDir.toAbsolutePath(path);
        }

        private <T> T guard(Path path, IOCall<T> call) throws IOException {
            try {
                return call.call();
            } catch (SecurityException e) {
                throw new SecurityException("Access to '" + path + "' is denied: GraalVM scripts can only access files inside the task working directory '" + workingDir.root + "', and the bundled standard library is read-only.", e);
            }
        }

        @Override
        public Path parsePath(URI uri) {
            return delegate.parsePath(uri);
        }

        @Override
        public Path parsePath(String path) {
            return delegate.parsePath(path);
        }

        @Override
        public void checkAccess(Path path, Set<? extends AccessMode> modes, LinkOption... linkOptions) throws IOException {
            guard(path, () -> {
                delegate.checkAccess(abs(path), modes, linkOptions);
                return null;
            });
        }

        @Override
        public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
            guard(dir, () -> {
                delegate.createDirectory(abs(dir), attrs);
                return null;
            });
        }

        @Override
        public void delete(Path path) throws IOException {
            guard(path, () -> {
                delegate.delete(abs(path));
                return null;
            });
        }

        @Override
        public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
            return guard(path, () -> delegate.newByteChannel(abs(path), options, attrs));
        }

        @Override
        public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
            return guard(dir, () -> delegate.newDirectoryStream(abs(dir), filter));
        }

        @Override
        public Path toAbsolutePath(Path path) {
            return workingDir.toAbsolutePath(path);
        }

        @Override
        public Path toRealPath(Path path, LinkOption... linkOptions) throws IOException {
            return guard(path, () -> delegate.toRealPath(abs(path), linkOptions));
        }

        @Override
        public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
            return guard(path, () -> delegate.readAttributes(abs(path), attributes, options));
        }

        @Override
        public void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
            guard(path, () -> {
                delegate.setAttribute(abs(path), attribute, value, options);
                return null;
            });
        }

        @Override
        public void copy(Path source, Path target, CopyOption... options) throws IOException {
            guard(target, () -> {
                delegate.copy(abs(source), abs(target), options);
                return null;
            });
        }

        @Override
        public void move(Path source, Path target, CopyOption... options) throws IOException {
            guard(source, () -> {
                delegate.move(abs(source), abs(target), options);
                return null;
            });
        }

        @Override
        public void createLink(Path link, Path existing) throws IOException {
            guard(link, () -> {
                delegate.createLink(abs(link), abs(existing));
                return null;
            });
        }

        @Override
        public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attrs) throws IOException {
            guard(link, () -> {
                delegate.createSymbolicLink(abs(link), target, attrs);
                return null;
            });
        }

        @Override
        public Path readSymbolicLink(Path link) throws IOException {
            return guard(link, () -> delegate.readSymbolicLink(abs(link)));
        }

        @Override
        public void setCurrentWorkingDirectory(Path currentWorkingDirectory) {
            delegate.setCurrentWorkingDirectory(currentWorkingDirectory);
        }

        @Override
        public String getSeparator() {
            return delegate.getSeparator();
        }

        @Override
        public String getPathSeparator() {
            return delegate.getPathSeparator();
        }

        @Override
        public String getMimeType(Path path) {
            return delegate.getMimeType(path);
        }

        @Override
        public Charset getEncoding(Path path) {
            return delegate.getEncoding(path);
        }

        @Override
        public Path getTempDirectory() {
            return delegate.getTempDirectory();
        }

        @Override
        public boolean isSameFile(Path path1, Path path2, LinkOption... options) throws IOException {
            return guard(path1, () -> delegate.isSameFile(abs(path1), abs(path2), options));
        }
    }
}
