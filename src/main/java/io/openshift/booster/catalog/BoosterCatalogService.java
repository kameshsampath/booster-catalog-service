/*
 * Copyright 2017 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Eclipse Public License version 1.0, available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package io.openshift.booster.catalog;

import io.openshift.booster.CopyFileVisitor;
import io.openshift.booster.catalog.spi.BoosterCatalogPathProvider;
import io.openshift.booster.catalog.spi.LocalBoosterCatalogPathProvider;
import io.openshift.booster.catalog.spi.NativeGitBoosterCatalogPathProvider;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.FlowableEmitter;
import org.yaml.snakeyaml.Yaml;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.openshift.booster.Files.removeFileExtension;

/**
 * This service reads from the Booster catalog Github repository in https://github.com/openshiftio/booster-catalog and
 * marshalls into {@link Booster} objects.
 *
 * @author <a href="mailto:ggastald@redhat.com">George Gastaldi</a>
 */
public class BoosterCatalogService implements BoosterCatalog {
    private static final String CLONED_BOOSTERS_DIR = ".boosters";
    private static final String METADATA_FILE = "metadata.json";

    /**
     * Files to be excluded from project creation
     */
    public static final List<String> EXCLUDED_PROJECT_FILES = Collections
            .unmodifiableList(Arrays.asList(".git", ".travis", ".travis.yml",
                    ".ds_store",
                    ".obsidian", ".gitmodules"));

    private static final Logger logger = Logger.getLogger(BoosterCatalogService.class.getName());

    private final Set<Booster> boosters = new ConcurrentSkipListSet<>(Comparator.comparing(Booster::getId));

    private final BoosterCatalogPathProvider provider;

    private final ExecutorService executor;

    private final CompletableFuture<Set<Booster>> result = new CompletableFuture<>();


    private volatile boolean indexingStarted = false;

    private BoosterCatalogService(BoosterCatalogPathProvider provider, ExecutorService executor) {
        this.provider = provider;
        this.executor = executor;
    }

    /**
     * Indexes the existing YAML files provided by the {@link BoosterCatalogPathProvider} implementation
     */
    public synchronized CompletableFuture<Set<Booster>> index() {
        if (!indexingStarted) {
            indexingStarted = true;
            CompletableFuture.runAsync(() -> {
                try {
                    doIndex();
                    result.complete(boosters);
                } catch (Exception ex) {
                    result.completeExceptionally(ex);
                }
            }, executor);
        }
        return result;
    }

    /**
     * Indexes the existing YAML files provided by the {@link BoosterCatalogPathProvider} implementation in reactive way
     * @return {@link Flowable<Booster>}
     */
    public Flowable<Booster> rxIndex() {
        Flowable<Booster> flowable = Flowable.empty();

        if (!indexingStarted) {
            indexingStarted = true;
            CompletableFuture<Path> catalogPathFuture = CompletableFuture.supplyAsync(() -> {
                Path catalogPath;
                try {
                    catalogPath = provider.createCatalogPath();
                } catch (IOException e) {
                    throw new IllegalStateException("Unable to index");
                }
                return catalogPath;
            });
            flowable = Flowable.create(subscriber ->
                    indexBoosters(catalogPathFuture.get(), subscriber), BackpressureStrategy.MISSING);
        }
        return flowable;
    }

    private void doIndex() throws Exception {
        Path catalogPath = provider.createCatalogPath();
        try {
            indexBoosters(catalogPath, null);
            logger.info(() -> "Finished content indexing");
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error while indexing", e);
            throw e;
        }
    }

    //removed boosters from the method param as its redundant
    private void indexBoosters(final Path catalogPath, FlowableEmitter<Booster> subscriber)
            throws IOException {
        Path moduleRoot = catalogPath.resolve(CLONED_BOOSTERS_DIR);
        Path metadataFile = catalogPath.resolve(METADATA_FILE);
        Map<String, Mission> missions = new HashMap<>();
        Map<String, Runtime> runtimes = new HashMap<>();
        Map<String, Version> versions = new HashMap<>();
        if (Files.exists(metadataFile)) {
            processMetadata(metadataFile, missions, runtimes);
        }
        Files.walkFileTree(catalogPath, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (Thread.interrupted()) {
                    return FileVisitResult.TERMINATE;
                }
                try {
                    File ioFile = file.toFile();
                    String fileName = ioFile.getName().toLowerCase();
                    // Skip any file that starts with .
                    if (!fileName.startsWith(".") && (fileName.endsWith(".yaml") || fileName.endsWith(".yml"))) {
                        String id = removeFileExtension(fileName);
                        Path modulePath = moduleRoot.resolve(id);
                        Optional<Booster> optional = indexBooster(id, catalogPath, file, modulePath, missions, runtimes, versions);
                        if (optional.isPresent()) {
                            if (subscriber != null) { //FIXME: after refactor i wil be removed
                                subscriber.onNext(optional.get());
                            }
                            BoosterCatalogService.this.boosters.add(optional.get()); // FIXME: after refactor i wil be removed
                        }
                    }
                } catch (Exception e) {
                    subscriber.onError(e);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (Thread.interrupted()) {
                    return FileVisitResult.TERMINATE;
                }
                return dir.startsWith(moduleRoot) || dir.getFileName().startsWith(".git")
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }
        });
        subscriber.onComplete();
    }

    /**
     * Process the metadataFile and adds to the specified missions and runtimes maps
     *
     * @param metadataFile
     * @param missions
     * @param runtimes
     */
    void processMetadata(Path metadataFile, Map<String, Mission> missions, Map<String, Runtime> runtimes) {
        logger.info(() -> "Reading metadata at " + metadataFile + " ...");

        try (BufferedReader reader = Files.newBufferedReader(metadataFile);
             JsonReader jsonReader = Json.createReader(reader)) {
            JsonObject index = jsonReader.readObject();
            index.getJsonArray("missions")
                    .stream()
                    .map(JsonObject.class::cast)
                    .map(e -> new Mission(e.getString("id"), e.getString("name")))
                    .forEach(m -> missions.put(m.getId(), m));

            index.getJsonArray("runtimes")
                    .stream()
                    .map(JsonObject.class::cast)
                    .map(e -> new Runtime(e.getString("id"), e.getString("name")))
                    .forEach(r -> runtimes.put(r.getId(), r));
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error while processing metadata " + metadataFile, e);
        }
    }

    /**
     * Takes a YAML file from the repository and indexes it
     *
     * @param file A YAML file from the booster-catalog repository
     * @return an {@link Optional} containing a {@link Booster}
     */
    @SuppressWarnings("unchecked")
    private Optional<Booster> indexBooster(String id, Path catalogPath, Path file, Path moduleDir,
                                           Map<String, Mission> missions,
                                           Map<String, Runtime> runtimes, Map<String, Version> versions) {
        logger.info(() -> "Indexing " + file + " ...");
        Yaml yaml = new Yaml(new YamlConstructor());
        Booster booster = null;
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            // Read YAML entry
            booster = yaml.loadAs(reader, Booster.class);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error while reading " + file, e);
        }
        if (booster != null) {
            try {
                // Booster ID = filename without extension
                booster.setId(id);

                String versionId;
                String runtimeId;
                String missionId;
                if (file.getParent().getParent().getParent().equals(catalogPath)) {
                    versionId = null;
                    runtimeId = file.getParent().toFile().getName();
                    missionId = file.getParent().getParent().toFile().getName();
                } else {
                    versionId = file.getParent().toFile().getName();
                    runtimeId = file.getParent().getParent().toFile().getName();
                    missionId = file.getParent().getParent().getParent().toFile().getName();
                }

                booster.setMission(missions.computeIfAbsent(missionId, Mission::new));
                booster.setRuntime(runtimes.computeIfAbsent(runtimeId, Runtime::new));
                if (versionId != null) {
                    booster.setVersion(versions.computeIfAbsent(versionId, Version::new));
                }

                booster.setContentPath(moduleDir);
                if (Files.notExists(moduleDir)) {
                    moduleDir = provider.createBoosterContentPath(booster);
                }
                Path metadataPath = moduleDir.resolve(booster.getBoosterDescriptorPath());
                try (BufferedReader metadataReader = Files.newBufferedReader(metadataPath)) {
                    Map<String, Object> metadata = yaml.loadAs(metadataReader, Map.class);
                    booster.setMetadata(metadata);

                    if (versionId != null) {
                        List<Map<String, Object>> vlist = (List<Map<String, Object>>) metadata.get("versions");
                        if (vlist != null) {
                            final Booster b = booster;
                            vlist
                                    .stream()
                                    .map(m -> new Version(Objects.toString(m.get("id")), Objects.toString(m.get("name"))))
                                    .filter(v -> b.getVersion().getId().equals(v.getId()))
                                    .forEach(v -> b.setVersion(v));
                        }
                    }
                }

                Path descriptionPath = moduleDir.resolve(booster.getBoosterDescriptionPath());
                if (Files.exists(descriptionPath)) {
                    byte[] descriptionContent = Files.readAllBytes(descriptionPath);
                    booster.setDescription(new String(descriptionContent));
                }
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Error while reading metadata from " + file, e);
            }
        }
        return Optional.ofNullable(booster);
    }

    /**
     * Copies the {@link Booster} contents to the specified {@link Path}
     */
    @Override
    public Path copy(Booster booster, Path projectRoot) throws IOException {
        Path modulePath = booster.getContentPath();
        return Files.walkFileTree(modulePath,
                new CopyFileVisitor(projectRoot,
                        (p) -> !EXCLUDED_PROJECT_FILES.contains(p.toFile().getName().toLowerCase())));
    }

    @Override
    public Set<Mission> getMissions(String... labels) {
        return filtered(boosters.stream(), labels)
                .map(Booster::getMission)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private Stream<Booster> filtered(Stream<Booster> stream, final String[] labels) {
        if (labels == null || labels.length == 0)
            return stream;
        return stream.filter(x -> {
            for (String label : labels) {
                if (!x.getLabels().contains(label)) {
                    return false;
                }
            }
            return true;
        });
    }

    @Override
    public Set<Mission> getMissions(Runtime runtime, String... labels) {
        Objects.requireNonNull(runtime, "Runtime should not be null");
        return filtered(boosters.stream(), labels)
                .filter(b -> runtime.equals(b.getRuntime()))
                .map(Booster::getMission)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Override
    public Set<Runtime> getRuntimes(String... labels) {
        return filtered(boosters.stream(), labels)
                .map(Booster::getRuntime)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Override
    public Set<Runtime> getRuntimes(Mission mission, String... labels) {
        if (mission == null) {
            return Collections.emptySet();
        }
        return filtered(boosters.stream(), labels)
                .filter(b -> mission.equals(b.getMission()))
                .map(Booster::getRuntime)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Override
    public Set<Version> getVersions(Mission mission, Runtime runtime, String... labels) {
        if (mission == null || runtime == null) {
            return Collections.emptySet();
        }
        return filtered(boosters.stream(), labels)
                .filter(b -> mission.equals(b.getMission()))
                .filter(b -> runtime.equals(b.getRuntime()))
                .filter(b -> b.getVersion() != null)
                .map(Booster::getVersion)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Override
    public Optional<Booster> getBooster(Mission mission, Runtime runtime, String... labels) {
        return getBooster(mission, runtime, null, labels);
    }

    @Override
    public Optional<Booster> getBooster(Mission mission, Runtime runtime, Version version, String... labels) {
        Objects.requireNonNull(mission, "Mission should not be null");
        Objects.requireNonNull(runtime, "Runtime should not be null");
        return filtered(boosters.stream(), labels)
                .filter(b -> mission.equals(b.getMission()))
                .filter(b -> runtime.equals(b.getRuntime()))
                .filter(b -> version == null || version.equals(b.getVersion()))
                .findFirst();
    }

    @Override
    public Collection<Booster> getBoosters(Runtime runtime, String... labels) {
        Objects.requireNonNull(runtime, "Runtime should not be null");
        return filtered(boosters.stream(), labels)
                .filter(b -> runtime.equals(b.getRuntime()))
                .collect(Collectors.toSet());
    }

    @Override
    public Collection<Booster> getBoosters(String... labels) {
        return filtered(boosters.stream(), labels).collect(Collectors.toCollection(HashSet::new));
    }

    /**
     * {@link BoosterCatalogService} Builder class
     *
     * @author <a href="mailto:ggastald@redhat.com">George Gastaldi</a>
     */
    public static class Builder {
        private String catalogRepositoryURI = "https://github.com/fabric8-launch/booster-catalog.git";
        private String catalogRef = "master";
        private Path rootDir;
        private BoosterCatalogPathProvider pathProvider;
        private ExecutorService executor;

        public Builder catalogRef(String catalogRef) {
            this.catalogRef = catalogRef;
            return this;
        }

        public Builder catalogRepository(String catalogRepositoryURI) {
            this.catalogRepositoryURI = catalogRepositoryURI;
            return this;
        }

        public Builder pathProvider(BoosterCatalogPathProvider pathProvider) {
            this.pathProvider = pathProvider;
            return this;
        }

        public Builder executor(ExecutorService executor) {
            this.executor = executor;
            return this;
        }

        public Builder rootDir(Path root) {
            this.rootDir = root;
            return this;
        }

        public BoosterCatalogService build() {
            assert catalogRef != null : "Catalog Ref is required";
            BoosterCatalogPathProvider provider = this.pathProvider;
            if (provider == null) {
                provider = discoverCatalogProvider();
            }
            assert provider != null : "BoosterCatalogPathProvider implementation is required";
            logger.info("Using " + provider.getClass().getName());
            if (executor == null) {
                executor = ForkJoinPool.commonPool();
            }
            return new BoosterCatalogService(provider, executor);
        }

        private BoosterCatalogPathProvider discoverCatalogProvider() {
            final BoosterCatalogPathProvider provider;
            // Check if we can use a local ZIP
            boolean ignoreLocalZip = Boolean.getBoolean("BOOSTER_CATALOG_IGNORE_LOCAL")
                    || Boolean.parseBoolean(System.getenv("BOOSTER_CATALOG_IGNORE_LOCAL"));
            if (ignoreLocalZip) {
                provider = new NativeGitBoosterCatalogPathProvider(catalogRepositoryURI, catalogRef, rootDir);
            } else {
                URL resource = getClass().getClassLoader()
                        .getResource(String.format("/booster-catalog-%s.zip", catalogRef));
                if (resource != null) {
                    provider = new LocalBoosterCatalogPathProvider(resource);
                } else {
                    // Resource not found, fallback to original Git resolution
                    provider = new NativeGitBoosterCatalogPathProvider(catalogRepositoryURI, catalogRef, rootDir);
                }
            }
            return provider;
        }
    }
}
