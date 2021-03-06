/*
 * Copyright 2013 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.asset;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Table;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.asset.sources.ArchiveSource;
import org.terasology.asset.sources.AssetSourceCollection;
import org.terasology.asset.sources.DirectorySource;
import org.terasology.engine.TerasologyConstants;
import org.terasology.entitySystem.prefab.Prefab;
import org.terasology.logic.behavior.asset.BehaviorTree;
import org.terasology.module.Module;
import org.terasology.module.ModuleEnvironment;
import org.terasology.naming.Name;
import org.terasology.persistence.ModuleContext;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class AssetManagerImpl implements AssetManager {

    private static final Logger logger = LoggerFactory.getLogger(AssetManager.class);

    private ModuleEnvironment environment;
    private Map<Name, AssetSource> assetSources = Maps.newHashMap();
    private Map<AssetType, Map<String, AssetLoader<?>>> assetLoaders = Maps.newEnumMap(AssetType.class);
    private Map<AssetUri, Asset<?>> assetCache = Maps.newHashMap();
    private Map<AssetUri, AssetSource> overrides = Maps.newHashMap();
    private Map<AssetType, AssetFactory<?, ?>> factories = Maps.newHashMap();
    private Map<AssetType, Table<Name, Name, AssetUri>> uriLookup = Maps.newHashMap();
    private ListMultimap<AssetType, AssetResolver<?, ?>> resolvers = ArrayListMultimap.create();

    public AssetManagerImpl(ModuleEnvironment environment) {
        for (AssetType type : AssetType.values()) {
            uriLookup.put(type, HashBasedTable.<Name, Name, AssetUri>create());
        }
        setEnvironment(environment);
    }

    @Override
    public ModuleEnvironment getEnvironment() {
        return environment;
    }

    @Override
    public void setEnvironment(ModuleEnvironment environment) {
        this.environment = environment;
        assetSources.clear();
        for (Module module : environment) {
            Collection<Path> location = module.getLocations();
            if (!location.isEmpty()) {
                List<AssetSource> sources = Lists.newArrayList();
                for (Path path : location) {
                    sources.add(createAssetSource(module.getId(), path));
                }
                AssetSource source = new AssetSourceCollection(module.getId(), sources);
                assetSources.put(source.getSourceId(), source);

                for (AssetUri asset : source.list()) {
                    uriLookup.get(asset.getAssetType()).put(asset.getAssetName(), asset.getModuleName(), asset);
                }
            }
        }
        applyOverrides();
        refresh();
    }

    private AssetSource createAssetSource(Name id, Path path) {
        if (Files.isRegularFile(path)) {
            return new ArchiveSource(id, path.toFile(), TerasologyConstants.ASSETS_SUBDIRECTORY, TerasologyConstants.OVERRIDES_SUBDIRECTORY,
                    TerasologyConstants.DELTAS_SUBDIRECTORY);
        } else {
            return new DirectorySource(id, path.resolve(TerasologyConstants.ASSETS_SUBDIRECTORY),
                    path.resolve(TerasologyConstants.OVERRIDES_SUBDIRECTORY), path.resolve(TerasologyConstants.DELTAS_SUBDIRECTORY));
        }
    }

    @Override
    public <T extends Asset<U>, U extends AssetData> void addResolver(AssetType assetType, AssetResolver<T, U> resolver) {
        resolvers.put(assetType, resolver);
    }

    @Override
    public void setAssetFactory(AssetType type, AssetFactory<?, ?> factory) {
        factories.put(type, factory);
    }

    @Override
    public List<AssetUri> resolveAll(AssetType type, String name) {
        AssetUri uri = new AssetUri(type, name);
        if (uri.isValid()) {
            return Lists.newArrayList(uri);
        }

        return resolveAll(type, new Name(name));
    }

    @Override
    public List<AssetUri> resolveAll(AssetType type, Name name) {
        List<AssetUri> results = Lists.newArrayList(uriLookup.get(type).row(name).values());
        for (AssetResolver<?, ?> resolver : resolvers.get(type)) {
            AssetUri additionalUri = resolver.resolve(name);
            if (additionalUri != null) {
                results.add(additionalUri);
            }
        }
        return results;
    }

    @Override
    public AssetUri resolve(AssetType type, String name) {
        List<AssetUri> possibilities = resolveAll(type, name);
        switch (possibilities.size()) {
            case 0:
                logger.warn("Failed to resolve {}:{}", type, name);
                return null;
            case 1:
                return possibilities.get(0);
            default:
                Module context = ModuleContext.getContext();
                if (context != null) {
                    Set<Name> dependencies = environment.getDependencyNamesOf(context.getId());
                    Iterator<AssetUri> iterator = possibilities.iterator();
                    while (iterator.hasNext()) {
                        AssetUri possibleUri = iterator.next();
                        if (context.getId().equals(possibleUri.getModuleName())) {
                            return possibleUri;
                        }
                        if (!dependencies.contains(possibleUri.getModuleName())) {
                            iterator.remove();
                        }
                    }
                    if (possibilities.size() == 1) {
                        return possibilities.get(0);
                    }
                }
                logger.warn("Failed to resolve {}:{} - too many valid matches {}", type, name, possibilities);
                return null;
        }
    }

    @Override
    public <T extends AssetData> T resolveAndLoadData(AssetType type, String name, Class<T> dataClass) {
        AssetData data = resolveAndLoadData(type, name);
        if (dataClass.isInstance(data)) {
            return dataClass.cast(data);
        }
        return null;
    }

    @Override
    public <T extends AssetData> T resolveAndTryLoadData(AssetType type, String name, Class<T> dataClass) {
        AssetData data = resolveAndTryLoadData(type, name);
        if (dataClass.isInstance(data)) {
            return dataClass.cast(data);
        }
        return null;
    }

    @Override
    public AssetData resolveAndLoadData(AssetType type, String name) {
        AssetUri uri = resolve(type, name);
        if (uri != null) {
            return loadAssetData(uri, true);
        }
        return null;
    }

    @Override
    public AssetData resolveAndTryLoadData(AssetType type, String name) {
        AssetUri uri = resolve(type, name);
        if (uri != null) {
            return loadAssetData(uri, false);
        }
        return null;
    }

    @Override
    public <T extends Asset<?>> T resolveAndLoad(AssetType type, String name, Class<T> assetClass) {
        Asset<?> result = resolveAndLoad(type, name);
        if (assetClass.isInstance(result)) {
            return assetClass.cast(result);
        }
        return null;
    }

    @Override
    public <T extends Asset<?>> T resolveAndTryLoad(AssetType type, String name, Class<T> assetClass) {
        Asset<?> result = resolveAndTryLoad(type, name);
        if (assetClass.isInstance(result)) {
            return assetClass.cast(result);
        }
        return null;
    }

    @Override
    public Asset<?> resolveAndLoad(AssetType type, String name) {
        AssetUri uri = resolve(type, name);
        if (uri != null) {
            return loadAsset(uri, false);
        }
        return null;
    }

    @Override
    public Asset<?> resolveAndTryLoad(AssetType type, String name) {
        AssetUri uri = resolve(type, name);
        if (uri != null) {
            return loadAsset(uri, true);
        }
        return null;
    }

    @Override
    public <T> T tryLoadAssetData(AssetUri uri, Class<T> type) {
        AssetData data = loadAssetData(uri, false);
        if (type.isInstance(data)) {
            return type.cast(data);
        }
        return null;
    }

    @Override
    public <T> T loadAssetData(AssetUri uri, Class<T> type) {
        AssetData data = loadAssetData(uri, true);
        if (type.isInstance(data)) {
            return type.cast(data);
        }
        return null;
    }

    @Override
    public void register(AssetType type, String extension, AssetLoader<?> loader) {
        Map<String, AssetLoader<?>> assetTypeMap = assetLoaders.get(type);
        if (assetTypeMap == null) {
            assetTypeMap = Maps.newHashMap();
            assetLoaders.put(type, assetTypeMap);
        }
        assetTypeMap.put(extension.toLowerCase(Locale.ENGLISH), loader);
    }

    @Override
    public <T extends Asset<?>> T tryLoadAsset(AssetUri uri, Class<T> type) {
        Asset<?> result = loadAsset(uri, false);
        if (type.isInstance(result)) {
            return type.cast(result);
        }
        return null;
    }

    @Override
    public Asset<?> loadAsset(AssetUri uri) {
        return loadAsset(uri, true);
    }

    @Override
    public <D extends AssetData> void reload(Asset<D> asset) {
        AssetData data = loadAssetData(asset.getURI(), false);
        if (data != null) {
            try {
                asset.reload((D) data);
            } catch (Exception e) {
                logger.error("Could not reload asset " + asset.getURI(), e);
            }
        }
    }

    @Override
    public <T extends Asset<?>> T loadAsset(AssetUri uri, Class<T> assetClass) {
        Asset<?> result = loadAsset(uri, true);
        if (assetClass.isInstance(result)) {
            return assetClass.cast(result);
        }
        return null;
    }

    private AssetData loadAssetData(AssetUri uri, boolean logErrors) {
        if (!uri.isValid()) {
            return null;
        }

        List<URL> urls = getAssetURLs(uri);
        if (urls.size() == 0) {
            if (logErrors) {
                logger.warn("Unable to resolve asset: {}", uri);
            }
            return null;
        }

        for (URL url : urls) {
            int extensionIndex = url.toString().lastIndexOf('.');
            if (extensionIndex == -1) {
                continue;
            }

            String extension = url.toString().substring(extensionIndex + 1).toLowerCase(Locale.ENGLISH);
            Map<String, AssetLoader<?>> extensionMap = assetLoaders.get(uri.getAssetType());
            if (extensionMap == null) {
                continue;
            }

            AssetLoader<?> loader = extensionMap.get(extension);
            if (loader == null) {
                continue;
            }

            Module module = environment.get(uri.getModuleName());
            List<URL> deltas;
            if (uri.getAssetType().isDeltaSupported()) {
                deltas = Lists.newArrayList();
                for (Module deltaModule : environment.getModulesOrderedByDependencies()) {
                    AssetSource source = assetSources.get(deltaModule.getId());
                    if (source != null) {
                        deltas.addAll(source.getDelta(uri));
                    }
                }
            } else {
                deltas = Collections.emptyList();
            }
            try (InputStream stream = AccessController.doPrivileged(new PrivilegedOpenStream(url))) {
                urls.remove(url);
                urls.add(0, url);
                return loader.load(module, stream, urls, deltas);
            } catch (PrivilegedActionException e) {
                logger.error("Error reading asset {}", uri, e.getCause());
                return null;
            } catch (IOException ioe) {
                logger.error("Error reading asset {}", uri, ioe);
                return null;
            }
        }
        logger.warn("Unable to resolve asset: {}", uri);
        return null;
    }

    private <D extends AssetData> Asset<?> loadAsset(AssetUri uri, boolean logErrors) {
        if (!uri.isValid()) {
            return null;
        }

        Asset<?> asset = assetCache.get(uri);
        if (asset != null) {
            return asset;
        }

        AssetFactory<D, Asset<D>> factory = (AssetFactory<D, Asset<D>>) factories.get(uri.getAssetType());
        if (factory == null) {
            logger.error("No asset factory set for assets of type {}", uri.getAssetType());
            return null;
        }

        for (AssetResolver<?, ?> resolver : resolvers.get(uri.getAssetType())) {
            AssetResolver<Asset<D>, D> typedResolver = (AssetResolver<Asset<D>, D>) resolver;
            Asset<D> result = typedResolver.resolve(uri, factory);
            if (result != null) {
                assetCache.put(uri, result);
                return result;
            }
        }

        try (ModuleContext.ContextSpan ignored = ModuleContext.setContext(environment.get(uri.getModuleName()))) {
            AssetData data = loadAssetData(uri, logErrors);

            if (data != null) {
                // TODO: verify that data class matches factory data class type
                // for example: if (factory.getDataTypeClass().isInstance(data)) ..
                asset = factory.buildAsset(uri, (D) data);
                if (asset != null) {
                    logger.debug("Loaded {}", uri);
                    assetCache.put(uri, asset);
                }
            }
        } catch (Exception e) {
            logger.error("Error loading asset: {}", uri, e);
        }
        return asset;
    }

    @Override
    public void refresh() {
        List<Asset<?>> keepAndReload = Lists.newArrayList();
        List<Asset<?>> dispose = Lists.newArrayList();

        for (Asset<?> asset : assetCache.values()) {
            if (asset.getURI().getModuleName().equals(TerasologyConstants.ENGINE_MODULE) && !(asset instanceof Prefab) && !(asset instanceof BehaviorTree)) {
                keepAndReload.add(asset);
            } else {
                dispose.add(asset);
            }
        }

        for (Asset<?> asset : keepAndReload) {
            reload(asset);
        }

        for (Asset<?> asset : dispose) {
            dispose(asset);
        }
    }

    @Override
    public void dispose(Asset<?> asset) {
        if (!asset.isDisposed()) {
            try {
                asset.dispose();
            } catch (Exception e) {
                logger.error("Could not dispose asset {}", asset.getURI(), e);
            }
        }
        assetCache.remove(asset.getURI());
    }

    @Override
    public <U extends AssetData> Asset<U> generateAsset(AssetUri uri, U data) {
        AssetFactory<U, Asset<U>> assetFactory = (AssetFactory<U, Asset<U>>) factories.get(uri.getAssetType());
        if (assetFactory == null) {
            logger.warn("Unsupported asset type: {}", uri.getAssetType());
            return null;
        }
        Asset<U> asset = (Asset<U>) assetCache.get(uri);
        if (asset != null) {
            logger.debug("Reloading {} with newly generated data", uri);
            asset.reload(data);
        } else {
            asset = assetFactory.buildAsset(uri, data);
            if (asset != null && !asset.isDisposed()) {
                assetCache.put(uri, asset);
            }
        }
        return asset;
    }

    @Override
    public <U extends AssetData> Asset<U> generateTemporaryAsset(AssetType type, U data) {
        return generateAsset(new AssetUri(type, "temp", UUID.randomUUID().toString()), data);
    }

    @Override
    public <D extends AssetData> void removeAssetSource(AssetSource source) {
        assetSources.remove(source.getSourceId());
        for (AssetUri override : source.listOverrides()) {
            if (overrides.get(override).equals(source)) {
                overrides.remove(override);
                Asset<D> asset = (Asset<D>) assetCache.get(override);
                if (asset != null) {
                    if (TerasologyConstants.ENGINE_MODULE.equals(override.getModuleName())) {
                        AssetData data = loadAssetData(override, true);
                        asset.reload((D) data);
                    } else {
                        dispose(asset);
                    }
                }
            }
        }
        for (Table<Name, Name, AssetUri> table : uriLookup.values()) {
            Map<Name, AssetUri> columnMap = table.column(source.getSourceId());
            for (AssetUri value : columnMap.values()) {
                Asset<?> asset = assetCache.remove(value);
                if (asset != null) {
                    asset.dispose();
                }
            }
            columnMap.clear();
        }
    }

    @Override
    public void applyOverrides() {
        overrides.clear();
        for (AssetSource assetSource : assetSources.values()) {
            for (AssetUri overrideURI : assetSource.listOverrides()) {
                overrides.put(overrideURI, assetSource);
            }
        }
    }

    @Override
    public Iterable<AssetUri> listAssets() {
        return new Iterable<AssetUri>() {

            @Override
            public Iterator<AssetUri> iterator() {
                return new AllAssetIterator();
            }
        };
    }

    @Override
    public Iterable<AssetUri> listAssets(final AssetType type) {
        return new Iterable<AssetUri>() {

            @Override
            public Iterator<AssetUri> iterator() {
                return new TypedAssetIterator(type);
            }
        };
    }

    @Override
    public <T> Iterable<T> listLoadedAssets(final AssetType type, Class<T> assetClass) {
        List<T> results = Lists.newArrayList();
        for (Map.Entry<AssetUri, Asset<?>> entry : assetCache.entrySet()) {
            if (entry.getKey().getAssetType() == type && assetClass.isInstance(entry.getValue())) {
                results.add(assetClass.cast(entry.getValue()));
            }
        }
        return results;
    }

    @Override
    public Iterable<Name> listModuleNames() {
        return assetSources.keySet();
    }

    @Override
    public List<URL> getAssetURLs(AssetUri uri) {
        AssetSource overrideSource = overrides.get(uri);
        if (overrideSource != null) {
            return overrideSource.getOverride(uri);
        } else {
            AssetSource source = assetSources.get(uri.getModuleName());
            if (source != null) {
                return source.get(uri);
            }
        }
        return Lists.newArrayList();
    }

    @Override
    public InputStream getAssetStream(AssetUri uri) throws IOException {
        final List<URL> assetURLs = getAssetURLs(uri);

        if (assetURLs.isEmpty()) {
            return null;
        }

        try {
            return AccessController.doPrivileged(new PrivilegedOpenStream(assetURLs.get(0)));
        } catch (PrivilegedActionException e) {
            throw new IOException(e.getCause().getMessage(), e.getCause());
        }
    }

    private class AllAssetIterator implements Iterator<AssetUri> {
        Iterator<AssetSource> sourceIterator;
        Iterator<AssetUri> currentUriIterator;
        AssetUri next;

        public AllAssetIterator() {
            sourceIterator = assetSources.values().iterator();
            if (sourceIterator.hasNext()) {
                currentUriIterator = sourceIterator.next().list().iterator();
            } else {
                currentUriIterator = Collections.emptyIterator();
            }
            iterate();
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public AssetUri next() {
            AssetUri result = next;
            iterate();
            return result;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

        private void iterate() {
            while (!currentUriIterator.hasNext() && sourceIterator.hasNext()) {
                currentUriIterator = sourceIterator.next().list().iterator();
            }
            if (currentUriIterator.hasNext()) {
                next = currentUriIterator.next();
            } else {
                next = null;
            }
        }
    }

    private class TypedAssetIterator implements Iterator<AssetUri> {
        AssetType type;
        Iterator<AssetSource> sourceIterator;
        Iterator<AssetUri> currentUriIterator;
        AssetUri next;

        public TypedAssetIterator(AssetType type) {
            this.type = type;
            sourceIterator = assetSources.values().iterator();
            if (sourceIterator.hasNext()) {
                currentUriIterator = sourceIterator.next().list(type).iterator();
            } else {
                currentUriIterator = Collections.emptyIterator();
            }
            iterate();
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public AssetUri next() {
            AssetUri result = next;
            iterate();
            return result;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

        private void iterate() {
            while (!currentUriIterator.hasNext() && sourceIterator.hasNext()) {
                currentUriIterator = sourceIterator.next().list(type).iterator();
            }
            if (currentUriIterator.hasNext()) {
                next = currentUriIterator.next();
            } else {
                next = null;
            }
        }
    }
}
