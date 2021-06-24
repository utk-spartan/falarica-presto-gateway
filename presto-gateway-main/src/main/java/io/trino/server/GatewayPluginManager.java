///*
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// *     http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//package io.trino.server;
//
//import com.google.common.collect.ImmutableList;
//import com.google.common.collect.Ordering;
//import io.airlift.log.Logger;
//import io.airlift.node.NodeInfo;
//import io.airlift.resolver.ArtifactResolver;
//import io.airlift.resolver.DefaultArtifact;
//import io.trino.connector.ConnectorManager;
//import io.trino.eventlistener.EventListenerManager;
//import io.trino.execution.resourcegroups.ResourceGroupManager;
//import io.trino.metadata.MetadataManager;
//import io.trino.security.AccessControlManager;
//import io.trino.security.GroupProviderManager;
//import io.trino.server.security.PasswordAuthenticatorManager;
//import io.trino.spi.Plugin;
//import io.trino.spi.SteerDGroupProviderPlugin;
//import io.trino.spi.block.BlockEncoding;
//import io.trino.spi.classloader.ThreadContextClassLoader;
//import io.trino.spi.connector.ConnectorFactory;
//import io.trino.spi.eventlistener.EventListenerFactory;
//import io.trino.spi.resourcegroups.ResourceGroupConfigurationManagerFactory;
//import io.trino.spi.security.GroupProviderFactory;
//import io.trino.spi.security.PasswordAuthenticatorFactory;
//import io.trino.spi.security.SystemAccessControlFactory;
//import io.trino.spi.session.SessionPropertyConfigurationManagerFactory;
//import io.trino.spi.type.ParametricType;
//import io.trino.spi.type.Type;
//import org.sonatype.aether.artifact.Artifact;
//
//import javax.annotation.concurrent.ThreadSafe;
//import javax.inject.Inject;
//
//import java.io.File;
//import java.io.IOException;
//import java.net.URL;
//import java.util.ArrayList;
//import java.util.Arrays;
//import java.util.Collections;
//import java.util.List;
//import java.util.ServiceLoader;
//import java.util.Set;
//import java.util.concurrent.atomic.AtomicBoolean;
//import java.util.function.Supplier;
//
//import static com.google.common.base.Preconditions.checkState;
//import static io.trino.metadata.FunctionExtractor.extractFunctions;
//
//@ThreadSafe
//public class GatewayPluginManager
//{
//    protected static final ImmutableList<String> SPI_PACKAGES = ImmutableList.<String>builder()
//            .add("io.trino.spi.")
//            .add("com.fasterxml.jackson.annotation.")
//            .add("io.airlift.slice.")
//            .add("org.openjdk.jol.")
//            .build();
//
//    private static final Logger log = Logger.get(PluginManager.class);
//
//    protected final ConnectorManager connectorManager;
//    protected final MetadataManager metadataManager;
//    protected final ResourceGroupManager<?> resourceGroupManager;
//    protected final AccessControlManager accessControlManager;
//    protected final PasswordAuthenticatorManager passwordAuthenticatorManager;
//    protected final EventListenerManager eventListenerManager;
//    protected final GroupProviderManager groupProviderManager;
//    protected final SessionPropertyDefaults sessionPropertyDefaults;
//    protected final ArtifactResolver resolver;
//    protected final File installedPluginsDir;
//    protected final List<String> plugins;
//    protected final AtomicBoolean pluginsLoading = new AtomicBoolean();
//    protected final AtomicBoolean pluginsLoaded = new AtomicBoolean();
//
//    @Inject
//    public GatewayPluginManager(
//            NodeInfo nodeInfo,
//            PluginManagerConfig config,
//            ConnectorManager connectorManager,
//            MetadataManager metadataManager,
//            ResourceGroupManager resourceGroupManager,
//            AccessControlManager accessControlManager,
//            PasswordAuthenticatorManager passwordAuthenticatorManager,
//            EventListenerManager eventListenerManager,
//            GroupProviderManager groupProviderManager,
//            SessionPropertyDefaults sessionPropertyDefaults)
//    {
//        requireNonNull(nodeInfo, "nodeInfo is null");
//        requireNonNull(config, "config is null");
//
//        installedPluginsDir = config.getInstalledPluginsDir();
//        if (config.getPlugins() == null) {
//            this.plugins = ImmutableList.of();
//        }
//        else {
//            this.plugins = ImmutableList.copyOf(config.getPlugins());
//        }
//        this.resolver = new ArtifactResolver(config.getMavenLocalRepository(), config.getMavenRemoteRepository());
//
//        this.connectorManager = requireNonNull(connectorManager, "connectorManager is null");
//        this.metadataManager = requireNonNull(metadataManager, "metadataManager is null");
//        this.resourceGroupManager = requireNonNull(resourceGroupManager, "resourceGroupManager is null");
//        this.accessControlManager = requireNonNull(accessControlManager, "accessControlManager is null");
//        this.passwordAuthenticatorManager = requireNonNull(passwordAuthenticatorManager, "passwordAuthenticatorManager is null");
//        this.eventListenerManager = requireNonNull(eventListenerManager, "eventListenerManager is null");
//        this.groupProviderManager = requireNonNull(groupProviderManager, "groupProviderManager is null");
//        this.sessionPropertyDefaults = requireNonNull(sessionPropertyDefaults, "sessionPropertyDefaults is null");
//    }
//
//    protected void loadPlugin(String plugin)
//            throws Exception
//    {
//        log.info("-- Loading plugin %s --", plugin);
//        PluginClassLoader pluginClassLoader = buildClassLoader(plugin);
//        try (ThreadContextClassLoader ignored = new ThreadContextClassLoader(pluginClassLoader)) {
//            loadPlugin(pluginClassLoader);
//        }
//        log.info("-- Finished loading plugin %s --", plugin);
//    }
//
//    public void loadPlugins()
//            throws Exception
//    {
//        if (!pluginsLoading.compareAndSet(false, true)) {
//            return;
//        }
//
//        for (File file : listFiles(installedPluginsDir)) {
//            if (file.isDirectory()) {
//                loadPlugin(file.getAbsolutePath());
//            }
//        }
//
//        for (String plugin : plugins) {
//            loadPlugin(plugin);
//        }
//
//        // load steerd plugins
//        // it will be better to create the plugin as a separate project
//        Plugin groupPlugin = new SteerDGroupProviderPlugin();
//        ServiceLoader.load(Plugin.class, groupPlugin.getClass().getClassLoader());
//        for (GroupProviderFactory groupProviderFactory : groupPlugin.getGroupProviderFactories()) {
//            log.info("Registering group provider %s", groupProviderFactory.getName());
//            groupProviderManager.addGroupProviderFactory(groupProviderFactory);
//        }
//
//        metadataManager.verifyComparableOrderableContract();
//
//        pluginsLoaded.set(true);
//    }
//
//    protected void loadPlugin(PluginClassLoader pluginClassLoader)
//    {
//        ServiceLoader<Plugin> serviceLoader = ServiceLoader.load(Plugin.class, pluginClassLoader);
//        List<Plugin> plugins = ImmutableList.copyOf(serviceLoader);
//        checkState(!plugins.isEmpty(), "No service providers of type %s", Plugin.class.getName());
//        for (Plugin plugin : plugins) {
//            log.info("Installing %s", plugin.getClass().getName());
//            installPlugin(plugin, pluginClassLoader::duplicate);
//        }
//    }
//
//    public void installPlugin(Plugin plugin, Supplier<ClassLoader> duplicatePluginClassLoaderFactory)
//    {
//        installPluginInternal(plugin, duplicatePluginClassLoaderFactory);
//        metadataManager.verifyComparableOrderableContract();
//    }
//
//    protected void installPluginInternal(Plugin plugin, Supplier<ClassLoader> duplicatePluginClassLoaderFactory)
//    {
//        for (BlockEncoding blockEncoding : plugin.getBlockEncodings()) {
//            log.info("Registering block encoding %s", blockEncoding.getName());
//            metadataManager.addBlockEncoding(blockEncoding);
//        }
//
//        for (Type type : plugin.getTypes()) {
//            log.info("Registering type %s", type.getTypeSignature());
//            metadataManager.addType(type);
//        }
//
//        for (ParametricType parametricType : plugin.getParametricTypes()) {
//            log.info("Registering parametric type %s", parametricType.getName());
//            metadataManager.addParametricType(parametricType);
//        }
//
//        for (ConnectorFactory connectorFactory : plugin.getConnectorFactories()) {
//            log.info("Registering connector %s", connectorFactory.getName());
//            connectorManager.addConnectorFactory(connectorFactory, duplicatePluginClassLoaderFactory);
//        }
//
//        for (Class<?> functionClass : plugin.getFunctions()) {
//            log.info("Registering functions from %s", functionClass.getName());
//            metadataManager.addFunctions(extractFunctions(functionClass));
//        }
//
//        for (SessionPropertyConfigurationManagerFactory sessionConfigFactory : plugin.getSessionPropertyConfigurationManagerFactories()) {
//            log.info("Registering session property configuration manager %s", sessionConfigFactory.getName());
//            sessionPropertyDefaults.addConfigurationManagerFactory(sessionConfigFactory);
//        }
//
//        for (ResourceGroupConfigurationManagerFactory configurationManagerFactory : plugin.getResourceGroupConfigurationManagerFactories()) {
//            log.info("Registering resource group configuration manager %s", configurationManagerFactory.getName());
//            resourceGroupManager.addConfigurationManagerFactory(configurationManagerFactory);
//        }
//
//        for (SystemAccessControlFactory accessControlFactory : plugin.getSystemAccessControlFactories()) {
//            log.info("Registering system access control %s", accessControlFactory.getName());
//            accessControlManager.addSystemAccessControlFactory(accessControlFactory);
//        }
//
//        for (PasswordAuthenticatorFactory authenticatorFactory : plugin.getPasswordAuthenticatorFactories()) {
//            log.info("Registering password authenticator %s", authenticatorFactory.getName());
//            passwordAuthenticatorManager.addPasswordAuthenticatorFactory(authenticatorFactory);
//        }
//
//        for (EventListenerFactory eventListenerFactory : plugin.getEventListenerFactories()) {
//            log.info("Registering event listener %s", eventListenerFactory.getName());
//            eventListenerManager.addEventListenerFactory(eventListenerFactory);
//        }
//
//        for (GroupProviderFactory groupProviderFactory : plugin.getGroupProviderFactories()) {
//            log.info("Registering group provider %s", groupProviderFactory.getName());
//            groupProviderManager.addGroupProviderFactory(groupProviderFactory);
//        }
//    }
//
//    protected PluginClassLoader buildClassLoader(String plugin)
//            throws Exception
//    {
//        File file = new File(plugin);
//        if (file.isFile() && (file.getName().equals("pom.xml") || file.getName().endsWith(".pom"))) {
//            return buildClassLoaderFromPom(file);
//        }
//        if (file.isDirectory()) {
//            return buildClassLoaderFromDirectory(file);
//        }
//        return buildClassLoaderFromCoordinates(plugin);
//    }
//
//    protected PluginClassLoader buildClassLoaderFromPom(File pomFile)
//            throws Exception
//    {
//        List<Artifact> artifacts = resolver.resolvePom(pomFile);
//        PluginClassLoader classLoader = createClassLoader(artifacts, pomFile.getPath());
//
//        Artifact artifact = artifacts.get(0);
//        Set<String> plugins = discoverPlugins(artifact, classLoader);
//        if (!plugins.isEmpty()) {
//            File root = new File(artifact.getFile().getParentFile().getCanonicalFile(), "plugin-discovery");
//            writePluginServices(plugins, root);
//            log.debug("    %s", root);
//            classLoader = classLoader.withUrl(root.toURI().toURL());
//        }
//
//        return classLoader;
//    }
//
//    protected PluginClassLoader buildClassLoaderFromDirectory(File dir)
//            throws Exception
//    {
//        log.debug("Classpath for %s:", dir.getName());
//        List<URL> urls = new ArrayList<>();
//        for (File file : listFiles(dir)) {
//            log.debug("    %s", file);
//            urls.add(file.toURI().toURL());
//        }
//        return createClassLoader(urls);
//    }
//
//    protected PluginClassLoader buildClassLoaderFromCoordinates(String coordinates)
//            throws Exception
//    {
//        Artifact rootArtifact = new DefaultArtifact(coordinates);
//        List<Artifact> artifacts = resolver.resolveArtifacts(rootArtifact);
//        return createClassLoader(artifacts, rootArtifact.toString());
//    }
//
//    protected PluginClassLoader createClassLoader(List<Artifact> artifacts, String name)
//            throws IOException
//    {
//        log.debug("Classpath for %s:", name);
//        List<URL> urls = new ArrayList<>();
//        for (Artifact artifact : sortedArtifacts(artifacts)) {
//            if (artifact.getFile() == null) {
//                throw new RuntimeException("Could not resolve artifact: " + artifact);
//            }
//            File file = artifact.getFile().getCanonicalFile();
//            log.debug("    %s", file);
//            urls.add(file.toURI().toURL());
//        }
//        return createClassLoader(urls);
//    }
//
//    protected PluginClassLoader createClassLoader(List<URL> urls)
//    {
//        ClassLoader parent = getClass().getClassLoader();
//        return new PluginClassLoader(urls, parent, SPI_PACKAGES);
//    }
//
//    protected static List<File> listFiles(File installedPluginsDir)
//    {
//        if (installedPluginsDir != null && installedPluginsDir.isDirectory()) {
//            File[] files = installedPluginsDir.listFiles();
//            if (files != null) {
//                Arrays.sort(files);
//                return ImmutableList.copyOf(files);
//            }
//        }
//        return ImmutableList.of();
//    }
//
//    protected static List<Artifact> sortedArtifacts(List<Artifact> artifacts)
//    {
//        List<Artifact> list = new ArrayList<>(artifacts);
//        Collections.sort(list, Ordering.natural().nullsLast().onResultOf(Artifact::getFile));
//        return list;
//    }
//}
