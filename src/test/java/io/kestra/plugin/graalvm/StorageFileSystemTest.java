package io.kestra.plugin.graalvm;

import io.kestra.core.storages.Storage;
import org.graalvm.polyglot.io.FileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileNotFoundException;
import java.net.URI;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class StorageFileSystemTest {
    @TempDir
    Path directory;

    @Test
    void stagesOnceOnAccessAndKeepsLocalEdits() throws Exception {
        Storage storage = mock(Storage.class);
        URI uri = URI.create("kestra:///input.csv");
        when(storage.getFile(uri)).thenReturn(content("original"));
        FileSystem fs = WorkingDirFileSystem.create(directory, storage);
        Path path = fs.parsePath(uri);
        assertThat(fs.parsePath(uri.toString()), is(path));
        assertThat(path.getParent(), is(directory.toRealPath()));
        verifyNoInteractions(storage);
        assertThat(Files.exists(path), is(false));

        assertThat(fs.readAttributes(path, "basic:size").get("size"), is(8L));
        assertThat(read(fs, path), is("original"));
        try (var output = Channels.newOutputStream(fs.newByteChannel(path, Set.of(StandardOpenOption.WRITE, StandardOpenOption.APPEND)))) {
            output.write(" edited".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(read(fs, fs.parsePath(uri)), is("original edited"));
        fs.delete(path);
        assertThrows(NoSuchFileException.class, () -> read(fs, fs.parsePath(uri)));
        verify(storage, times(1)).getFile(uri);
    }

    @Test
    void concurrentOpensDownloadOneCompleteCopy() throws Exception {
        Storage storage = mock(Storage.class);
        URI uri = URI.create("kestra:///input.csv");
        when(storage.getFile(uri)).thenAnswer(invocation -> content("complete"));
        FileSystem fs = WorkingDirFileSystem.create(directory, storage);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var reads = IntStream.range(0, 8)
                .mapToObj(i -> (Callable<String>) () -> read(fs, fs.parsePath(uri)))
                .toList();
            for (var result : executor.invokeAll(reads)) {
                assertThat(result.get(), is("complete"));
            }
        }
        verify(storage, times(1)).getFile(uri);
    }

    @Test
    void isolatesCopiesBetweenContexts() throws Exception {
        Storage storage = mock(Storage.class);
        URI uri = URI.create("kestra:///input.csv");
        when(storage.getFile(uri)).thenAnswer(invocation -> content("original"));
        FileSystem first = WorkingDirFileSystem.create(Files.createDirectory(directory.resolve("first")), storage);
        FileSystem second = WorkingDirFileSystem.create(Files.createDirectory(directory.resolve("second")), storage);
        Path firstPath = first.parsePath(uri);
        Path secondPath = second.parsePath(uri);
        assertThat(firstPath, not(secondPath));
        assertThat(read(first, firstPath), is("original"));
        assertThat(read(second, secondPath), is("original"));
        verify(storage, times(2)).getFile(uri);
    }

    @Test
    void preservesStorageAuthorizationFailure() throws Exception {
        Storage storage = mock(Storage.class);
        URI uri = URI.create("kestra:///restricted/%2e%2e/private.csv");
        SecurityException denied = new SecurityException("Storage read denied");
        when(storage.getFile(uri)).thenThrow(denied);
        FileSystem fs = WorkingDirFileSystem.create(directory, storage);
        Path path = fs.parsePath(uri);
        assertThat(assertThrows(SecurityException.class, () -> read(fs, path)), sameInstance(denied));
        assertThat(Files.exists(path), is(false));
        verify(storage).getFile(uri);
        verifyNoMoreInteractions(storage);
    }

    @Test
    void doesNotOverwriteOrDeletePreexistingDestination() throws Exception {
        Storage storage = mock(Storage.class);
        URI uri = URI.create("kestra:///input.csv");
        when(storage.getFile(uri)).thenReturn(content("download"));
        FileSystem fs = WorkingDirFileSystem.create(directory, storage);
        Path path = fs.parsePath(uri);
        Files.writeString(path, "preexisting");
        assertThrows(FileAlreadyExistsException.class, () -> read(fs, path));
        assertThat(Files.readString(path), is("preexisting"));
    }

    @Test
    void reportsMissingStorageFileWithoutCreatingLocalFile() throws Exception {
        Storage storage = mock(Storage.class);
        URI uri = URI.create("kestra:///missing.csv");
        when(storage.getFile(uri)).thenThrow(new FileNotFoundException(uri.toString()));
        FileSystem fs = WorkingDirFileSystem.create(directory, storage);
        Path path = fs.parsePath(uri);
        assertThrows(NoSuchFileException.class, () -> read(fs, path));
        assertThat(Files.exists(path), is(false));
    }

    @Test
    void removesPartialDownloadAndClosesStreamBeforeRetry() throws Exception {
        Storage storage = mock(Storage.class);
        URI uri = URI.create("kestra:///input.csv");
        var closed = new AtomicBoolean();
        InputStream broken = new InputStream() {
            private boolean first = true;
            @Override
            public int read() throws IOException {
                if (first) {
                    first = false;
                    return 'x';
                }
                throw new IOException("Download interrupted");
            }
            @Override
            public void close() {
                closed.set(true);
            }
        };
        when(storage.getFile(uri)).thenReturn(broken, content("complete"));
        FileSystem fs = WorkingDirFileSystem.create(directory, storage);
        Path path = fs.parsePath(uri);
        assertThrows(IOException.class, () -> read(fs, path));
        assertThat(Files.exists(path), is(false));
        assertThat(closed.get(), is(true));
        assertThat(read(fs, path), is("complete"));
    }

    @Test
    void deniesStagingThroughOutsideSymlink() throws Exception {
        Storage storage = mock(Storage.class);
        Path root = Files.createDirectory(directory.resolve("root"));
        Path outside = Files.writeString(directory.resolve("outside.csv"), "unchanged");
        FileSystem fs = WorkingDirFileSystem.create(root, storage);
        Path path = fs.parsePath("kestra:///input.csv");
        Files.createSymbolicLink(path, outside);
        assertThrows(SecurityException.class, () -> read(fs, path));
        assertThat(Files.readString(outside), is("unchanged"));
        verifyNoInteractions(storage);
    }

    @Test
    void neverUsesStorageUriAsDestinationPath() throws Exception {
        Storage storage = mock(Storage.class);
        URI uri = URI.create("kestra:///../../outside.csv");
        when(storage.getFile(uri)).thenReturn(content("data"));
        FileSystem fs = WorkingDirFileSystem.create(directory, storage);
        Path path = fs.parsePath(uri);
        assertThat(path.getParent(), is(directory.toRealPath()));
        assertThat(read(fs, path), is("data"));
        verify(storage).getFile(uri);
    }

    private static InputStream content(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(FileSystem fs, Path path) throws IOException {
        try (var input = Channels.newInputStream(fs.newByteChannel(path, Set.of(StandardOpenOption.READ)))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
