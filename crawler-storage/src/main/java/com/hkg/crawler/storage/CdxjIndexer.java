package com.hkg.crawler.storage;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Builds a CDXJ index — sorted by SURT key — from a stream of
 * {@link CdxjEntry}s. Output is one entry per line, bytewise-sortable so
 * downstream tooling (CDX server, wb-cli) can binary-search the file.
 *
 * <p>Sort strategy: in-memory sort for the single-shard case (entries
 * for one day-of-crawl are typically <100M and fit in RAM). Distributed
 * crawls would replace this with an external-merge-sort or a Spark job;
 * the interface stays the same.
 *
 * <p>Per blog §11: CDXJ + Parquet is the production layout (CDXJ for
 * point lookup; Parquet for analytics). This class produces CDXJ;
 * Parquet output is a separate path.
 */
public final class CdxjIndexer {

    /**
     * Write {@code entries} sorted by SURT key to {@code outputFile}.
     * Returns the number of entries written.
     */
    public long writeSortedIndex(Collection<CdxjEntry> entries, Path outputFile) throws IOException {
        List<CdxjEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator
            .comparing(CdxjEntry::surtKey)
            .thenComparing(CdxjEntry::timestamp));
        Files.createDirectories(outputFile.getParent() == null
            ? outputFile.toAbsolutePath().getParent()
            : outputFile.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
            for (CdxjEntry e : sorted) {
                w.write(e.toCdxjLine());
                w.newLine();
            }
        }
        return sorted.size();
    }

    /** Append a single entry to an existing unsorted CDXJ file (production: pre-sort phase). */
    public void appendUnsorted(CdxjEntry entry, Path outputFile) throws IOException {
        if (outputFile.getParent() != null) Files.createDirectories(outputFile.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(outputFile,
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND)) {
            w.write(entry.toCdxjLine());
            w.newLine();
        }
    }
}
