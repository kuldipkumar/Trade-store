package com.tradestore.repo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads repository files for the conformance tests; Maven runs tests from the project root. */
final class RepositoryFiles {

    private RepositoryFiles() {
    }

    static String read(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Any file, binary included: invalid UTF-8 bytes become replacement characters instead of an error. */
    static String readLenient(String path) {
        try {
            return new String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static boolean exists(String path) {
        return Files.exists(Path.of(path));
    }
}
