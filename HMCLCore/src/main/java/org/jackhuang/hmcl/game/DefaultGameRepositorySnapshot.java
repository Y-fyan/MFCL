/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.game;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jackhuang.hmcl.nativebridge.NativeBackend;

import java.io.IOException;
import java.nio.file.Path;

import java.util.*;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Default implementation of a repository index snapshot for [DefaultGameRepository].
///
/// Package-private repository code assembles an instance in an unpublished construction phase and
/// calls [#seal()] before exposing it as a [GameRepositorySnapshot]. Once sealed, its contents never
/// change.
///
/// Mutation methods are package-private: only code in `org.jackhuang.hmcl.game` may assemble a
/// snapshot. Subclasses such as HMCL-specific snapshots may override [#newEmpty()] to preserve
/// concrete type through [#mutableCopy()], analogous to
/// [DefaultGameInstance#withNewSnapshot(DefaultGameRepositorySnapshot)].
@NotNullByDefault
public class DefaultGameRepositorySnapshot implements GameRepositorySnapshot {
    protected final DefaultGameRepository repository;
    protected final DefaultGameRepositoryLayout layout;
    private Map<GameInstanceID, DefaultGameInstance> instances;
    private boolean sealed;

    /// Creates an empty unsealed snapshot for building a new snapshot.
    ///
    /// @param repository the owning repository
    /// @param layout     the layout for this snapshot
    public DefaultGameRepositorySnapshot(DefaultGameRepository repository, DefaultGameRepositoryLayout layout) {
        this.repository = repository;
        this.layout = layout;
        this.instances = new TreeMap<>();
        this.sealed = false;
    }

    /// Creates an empty unsealed snapshot of the same concrete type as this snapshot.
    ///
    /// @return a new empty unsealed snapshot
    protected DefaultGameRepositorySnapshot newEmpty() {
        return new DefaultGameRepositorySnapshot(repository, layout);
    }

    /// Freezes this snapshot so its instance map can no longer be modified.
    void seal() {
        if (!sealed) {
            instances = Collections.unmodifiableMap(new TreeMap<>(instances));
            sealed = true;
        }
    }

    private void checkMutable() {
        if (sealed) {
            throw new IllegalStateException("Snapshot has been published and cannot be modified");
        }
    }

    /// {@inheritDoc}
    @Override
    public DefaultGameRepository getRepository() {
        return repository;
    }

    /// {@inheritDoc}
    @Override
    public DefaultGameRepositoryLayout getLayout() {
        return layout;
    }

    /// Returns the instance with the given id.
    ///
    /// @param id the instance id
    /// @return the instance, or `null` when absent
    public @Nullable DefaultGameInstance get(GameInstanceID id) {
        return instances.get(id);
    }

    /// Returns the registered instance with the given id.
    ///
    /// @param id the instance id
    /// @return the registered instance
    /// @throws NoSuchGameInstanceException if the instance is absent
    public DefaultGameInstance getRegistered(GameInstanceID id) throws NoSuchGameInstanceException {
        DefaultGameInstance instance = instances.get(id);
        if (instance != null) {
            return instance;
        }
        throw new NoSuchGameInstanceException(id);
    }

    /// {@inheritDoc}
    @Override
    public boolean hasInstance(GameInstanceID instanceId) {
        return instances.containsKey(instanceId);
    }

    /// {@inheritDoc}
    @Override
    public DefaultGameInstance getInstance(GameInstanceID instanceId) throws NoSuchGameInstanceException {
        return getRegistered(instanceId);
    }

    /// {@inheritDoc}
    @Override
    public @Nullable DefaultGameInstance findInstance(GameInstanceID instanceId) {
        return instances.get(instanceId);
    }

    /// {@inheritDoc}
    @Override
    public int getInstanceCount() {
        return instances.size();
    }

    /// {@inheritDoc}
    @Override
    public Collection<? extends DefaultGameInstance> getInstances() {
        return List.copyOf(instances.values());
    }

    /// {@inheritDoc}
    @Override
    public Collection<GameInstanceManifest> getInstanceManifests() {
        return instances.values().stream()
                .map(instance -> instance.manifest)
                .toList();
    }

    /// Adds or replaces an instance in this unsealed snapshot.
    ///
    /// @param instance the instance bound to this snapshot
    void put(DefaultGameInstance instance) {
        checkMutable();
        instances.put(instance.getId(), instance);
    }

    /// Removes the instance with the given id.
    ///
    /// @param id the instance id
    void remove(GameInstanceID id) {
        checkMutable();
        instances.remove(id);
    }

    /// Remove invalid instances.
    /// TODO: In the future we could retain these instances and show their status in the UI.
    void removeInvalidInstances() {
        checkMutable();

        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (executable != null && !executable.isBlank()
                && Boolean.parseBoolean(System.getProperty("mfcl.native.instances.enabled", "true"))
                && instances.values().stream().allMatch(instance -> instance.getId().equals(instance.getManifest().id()))
                && instances.values().stream().anyMatch(instance -> instance.getManifest().inheritsFrom() != null)) {
            try {
                Set<GameInstanceID> invalid = NativeBackend.findInvalidInheritance(Path.of(executable),
                        instances.values().stream().map(DefaultGameInstance::getManifest).toList());
                for (GameInstanceID id : invalid) {
                    LOG.warning("Instance " + id + " has a missing inheritance ancestor");
                    instances.remove(id);
                }
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException e) {
                LOG.warning("Native inheritance check failed; using Java validation");
            }
        }

        HashSet<GameInstanceID> visited = null;
        loop:
        for (DefaultGameInstance instance : List.copyOf(instances.values())) {
            if (instance.getManifest().inheritsFrom() == null) {
                continue;
            }

            if (visited != null)
                visited.clear();
            else
                visited = new HashSet<>();

            DefaultGameInstance current = instance;
            visited.add(current.getId());
            while (current.getManifest().inheritsFrom() != null) {
                GameInstanceID parentId = current.getManifest().inheritsFrom();
                if (!visited.add(parentId)) {
                    // DefaultGameRepositorySnapshot.resolve can handle circular references
                    continue loop;
                }

                DefaultGameInstance parent = instances.get(parentId);
                if (parent == null) {
                    LOG.warning("Instance " + current.getId() + " inherits from missing instance " + parentId);

                    for (DefaultGameInstance toRemoved = instance; toRemoved != null; ) {
                        instances.remove(toRemoved.getId());
                        toRemoved = toRemoved.getManifest().inheritsFrom() != null
                                ? instances.get(toRemoved.getManifest().inheritsFrom())
                                : null;
                    }

                    break;
                }
                current = parent;
            }
        }

    }

    /// Creates an unpublished mutable copy with instances rebound to the copy.
    ///
    /// The caller must call [#seal()] before retaining or publishing the result as a snapshot.
    ///
    /// @return the mutable construction copy
    DefaultGameRepositorySnapshot mutableCopy() {
        DefaultGameRepositorySnapshot newSnapshot = newEmpty();
        for (DefaultGameInstance instance : instances.values()) {
            newSnapshot.put(instance.withNewSnapshot(newSnapshot));
        }
        return newSnapshot;
    }

    public GameInstanceManifest resolve(GameInstanceManifest manifest) throws NoSuchGameInstanceException {
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (manifest.inheritsFrom() != null && executable != null && !executable.isBlank()
                && Boolean.parseBoolean(System.getProperty("mfcl.native.instances.enabled", "true"))
                && instances.values().stream().allMatch(instance -> instance.getId().equals(instance.getManifest().id()))) {
            try {
                var plan = NativeBackend.planInheritance(Path.of(executable), manifest,
                        instances.values().stream().map(DefaultGameInstance::getManifest).toList());
                return resolvePlannedInheritance(manifest, plan);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException e) {
                LOG.warning("Native inheritance planning failed; using Java resolution");
            }
        }
        return resolve(manifest, new HashSet<>());
    }

    /// Validates a native chain against the snapshot and applies the original manifest merges.
    /// @param start manifest being resolved
    /// @param plan native inheritance chain
    /// @return resolved manifest
    /// @throws IOException if the plan does not match the snapshot
    private GameInstanceManifest resolvePlannedInheritance(GameInstanceManifest start, NativeBackend.InheritancePlan plan) throws IOException {
        List<GameInstanceManifest> chain = new ArrayList<>();
        Set<GameInstanceID> visited = new HashSet<>();
        GameInstanceManifest current = start;
        for (int i = 0; i < plan.chain().size(); i++) {
            if (!current.id().equals(plan.chain().get(i))) throw new IOException("Invalid native inheritance chain");
            chain.add(current);
            boolean last = i == plan.chain().size() - 1;
            @Nullable GameInstanceID parent = current.inheritsFrom();
            if (parent == null) {
                if (!last || plan.cycle()) throw new IOException("Invalid native inheritance termination");
                break;
            }
            if (!visited.add(current.id())) {
                if (!last || !plan.cycle()) throw new IOException("Invalid native inheritance cycle");
                break;
            }
            if (last || !parent.equals(plan.chain().get(i + 1))) throw new IOException("Invalid native inheritance parent");
            @Nullable DefaultGameInstance next = instances.get(parent);
            if (next == null) throw new IOException("Missing native inheritance parent");
            current = next.getManifest();
        }
        GameInstanceManifest terminal = chain.get(chain.size() - 1);
        GameInstanceManifest resolved;
        if (plan.cycle()) {
            LOG.warning("Found circular dependency instances: " + visited);
            resolved = (terminal.jar() == null ? terminal.withJar(terminal.id()) : terminal).withInheritsFrom(null);
        } else {
            resolved = terminal.isRoot() && terminal.patches() != null
                    ? new GameInstanceManifest(terminal.id()).withPatches(terminal.patches()) : terminal;
            resolved = resolved.withJar(terminal.jar() == null ? terminal.id() : terminal.jar());
        }
        resolved = applyManifestPatches(terminal, resolved);
        for (int i = chain.size() - 2; i >= 0; i--) {
            GameInstanceManifest child = chain.get(i);
            resolved = applyManifestPatches(child, child.merge(resolved));
        }
        return resolved;
    }

    /// Resolves official-layout inheritance and patches without launch-library deduplication.
    ///
    /// @param manifest      the manifest to resolve
    /// @param resolvedSoFar instance ids already visited in the inheritance chain
    /// @return the resolved manifest views
    /// @throws NoSuchGameInstanceException if an inherited parent is missing from this snapshot
    private GameInstanceManifest resolve(
            GameInstanceManifest manifest,
            @Nullable Set<GameInstanceID> resolvedSoFar) throws NoSuchGameInstanceException {
        GameInstanceManifest resolvedManifest;

        if (manifest.inheritsFrom() == null) {
            if (manifest.isRoot()) {
                resolvedManifest = manifest.patches() != null
                        ? new GameInstanceManifest(manifest.id()).withPatches(manifest.patches())
                        : manifest;
            } else {
                resolvedManifest = manifest;
            }
            resolvedManifest = resolvedManifest.withJar(manifest.jar() == null ? manifest.id() : manifest.jar());
        } else {
            if (resolvedSoFar == null) {
                resolvedSoFar = new HashSet<>();
            }

            // To maximize the compatibility.
            if (!resolvedSoFar.add(manifest.id())) {
                LOG.warning("Found circular dependency instances: " + resolvedSoFar);
                resolvedManifest = (manifest.jar() == null ? manifest.withJar(manifest.id()) : manifest)
                        .withInheritsFrom(null);
            } else {
                DefaultGameInstance parentInstance = instances.get(manifest.inheritsFrom());
                if (parentInstance == null) {
                    throw new NoSuchGameInstanceException(manifest.inheritsFrom());
                }

                // It is supposed to auto-install a version in getVersion.
                GameInstanceManifest parentResolved = resolve(parentInstance.getManifest(), resolvedSoFar);
                resolvedManifest = manifest.merge(parentResolved);
            }
        }

        return applyManifestPatches(manifest, resolvedManifest);
    }

    /// Applies this manifest's stable-priority patches to a resolved intermediate result.
    /// @param manifest source manifest
    /// @param resolvedManifest inherited intermediate manifest
    /// @return final manifest for this step
    private GameInstanceManifest applyManifestPatches(GameInstanceManifest manifest, GameInstanceManifest resolvedManifest) {
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (manifest.patches() != null && !manifest.patches().isEmpty() && executable != null && !executable.isBlank()
                && Boolean.parseBoolean(System.getProperty("mfcl.native.instances.enabled", "true"))) {
            try {
                return NativeManifestMerge.applyPatches(Path.of(executable), manifest, resolvedManifest);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException e) {
                LOG.warning("Native manifest patch application failed; using Java merge");
            }
        }
        var builder = new GameInstanceManifest.Builder(resolvedManifest);

        if (manifest.patches() != null && !manifest.patches().isEmpty()) {
            // Assume patches themselves do not have patches recursively.
            List<GameInstancePatch> sortedPatches = manifest.patches().stream()
                    .sorted(Comparator.comparing(GameInstancePatch::getPriority))
                    .toList();
            for (GameInstancePatch patch : sortedPatches) {
                builder.merge(patch);
            }
        }

        builder.setId(manifest.id());
        builder.setPatches(null);
        return builder.toManifest();
    }

}
