/*
 * Copyright 2010 the original author or authors.
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

package org.gradle.configuration;

import org.gradle.api.initialization.dsl.ScriptHandler;
import org.gradle.api.internal.DocumentationRegistry;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.SettingsInternal;
import org.gradle.api.internal.file.FileLookup;
import org.gradle.api.internal.file.collections.DirectoryFileTreeFactory;
import org.gradle.api.internal.initialization.ClassLoaderScope;
import org.gradle.api.internal.initialization.ScriptHandlerFactory;
import org.gradle.api.internal.initialization.ScriptHandlerInternal;
import org.gradle.api.internal.initialization.loadercache.ClassPathSnapshotter;
import org.gradle.api.internal.plugins.PluginManagerInternal;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.tasks.util.PatternSet;
import org.gradle.api.tasks.util.internal.PatternSets;
import org.gradle.groovy.scripts.*;
import org.gradle.groovy.scripts.internal.*;
import org.gradle.internal.Actions;
import org.gradle.internal.Factory;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.internal.service.DefaultServiceRegistry;
import org.gradle.logging.LoggingManagerInternal;
import org.gradle.model.dsl.internal.transform.ClosureCreationInterceptingVerifier;
import org.gradle.model.internal.inspect.ModelRuleSourceDetector;
import org.gradle.plugin.use.internal.*;

public class DefaultScriptPluginFactory implements ScriptPluginFactory {

    private final ScriptCompilerFactory scriptCompilerFactory;
    private final Factory<LoggingManagerInternal> loggingManagerFactory;
    private final Instantiator instantiator;
    private final ScriptHandlerFactory scriptHandlerFactory;
    private final PluginRequestApplicator pluginRequestApplicator;
    private final FileLookup fileLookup;
    private final DirectoryFileTreeFactory directoryFileTreeFactory;
    private final DocumentationRegistry documentationRegistry;
    private final ModelRuleSourceDetector modelRuleSourceDetector;
    private final ClassPathSnapshotter classpathSnapshotter;
    private final BuildScriptDataSerializer buildScriptDataSerializer = new BuildScriptDataSerializer();
    private final PluginRequestsSerializer pluginRequestsSerializer = new PluginRequestsSerializer();
    private final InjectedPluginClasspath injectedPluginClassPath;

    public DefaultScriptPluginFactory(ScriptCompilerFactory scriptCompilerFactory,
                                      Factory<LoggingManagerInternal> loggingManagerFactory,
                                      Instantiator instantiator,
                                      ScriptHandlerFactory scriptHandlerFactory,
                                      PluginRequestApplicator pluginRequestApplicator,
                                      FileLookup fileLookup,
                                      DirectoryFileTreeFactory directoryFileTreeFactory,
                                      DocumentationRegistry documentationRegistry,
                                      ModelRuleSourceDetector modelRuleSourceDetector,
                                      ClassPathSnapshotter classpathSnapshotter,
                                      InjectedPluginClasspath injectedPluginClasspath) {
        this.scriptCompilerFactory = scriptCompilerFactory;
        this.loggingManagerFactory = loggingManagerFactory;
        this.instantiator = instantiator;
        this.scriptHandlerFactory = scriptHandlerFactory;
        this.pluginRequestApplicator = pluginRequestApplicator;
        this.fileLookup = fileLookup;
        this.directoryFileTreeFactory = directoryFileTreeFactory;
        this.documentationRegistry = documentationRegistry;
        this.modelRuleSourceDetector = modelRuleSourceDetector;
        this.classpathSnapshotter = classpathSnapshotter;
        this.injectedPluginClassPath = injectedPluginClasspath;
    }

    public ScriptPlugin create(ScriptSource scriptSource, ScriptHandler scriptHandler, ClassLoaderScope targetScope, ClassLoaderScope baseScope, boolean topLevelScript) {
        return new ScriptPluginImpl(scriptSource, (ScriptHandlerInternal) scriptHandler, targetScope, baseScope, topLevelScript);
    }

    private class ScriptPluginImpl implements ScriptPlugin {
        private final ScriptSource scriptSource;
        private final ClassLoaderScope targetScope;
        private final ClassLoaderScope baseScope;
        private final ScriptHandlerInternal scriptHandler;
        private final boolean topLevelScript;

        public ScriptPluginImpl(ScriptSource scriptSource, ScriptHandlerInternal scriptHandler, ClassLoaderScope targetScope, ClassLoaderScope baseScope, boolean topLevelScript) {
            this.scriptSource = scriptSource;
            this.targetScope = targetScope;
            this.baseScope = baseScope;
            this.scriptHandler = scriptHandler;
            this.topLevelScript = topLevelScript;
        }

        public ScriptSource getSource() {
            return scriptSource;
        }

        public void apply(final Object target) {
            final DefaultServiceRegistry services = new DefaultServiceRegistry() {
                Factory<PatternSet> createPatternSetFactory() {
                    return PatternSets.getNonCachingPatternSetFactory();
                }
            };
            services.add(ScriptPluginFactory.class, DefaultScriptPluginFactory.this);
            services.add(ScriptHandlerFactory.class, scriptHandlerFactory);
            services.add(ClassLoaderScope.class, targetScope);
            services.add(LoggingManagerInternal.class, loggingManagerFactory.create());
            services.add(Instantiator.class, instantiator);
            services.add(ScriptHandler.class, scriptHandler);
            services.add(FileLookup.class, fileLookup);
            services.add(DirectoryFileTreeFactory.class, directoryFileTreeFactory);
            services.add(ModelRuleSourceDetector.class, modelRuleSourceDetector);

            final ScriptTarget scriptTarget = wrap(target);

            ScriptCompiler compiler = scriptCompilerFactory.createCompiler(scriptSource);

            // Pass 1, extract plugin requests and execute buildscript {}, ignoring (i.e. not even compiling) anything else

            Class<? extends BasicScript> scriptType = scriptTarget.getScriptClass();
            boolean supportsPluginsBlock = scriptTarget.getSupportsPluginsBlock();
            String onPluginBlockError = supportsPluginsBlock ? null : "Only Project build scripts can contain plugins {} blocks";
            String classpathClosureName = scriptTarget.getClasspathBlockName();
            InitialPassStatementTransformer initialPassStatementTransformer = new InitialPassStatementTransformer(classpathClosureName, onPluginBlockError, scriptSource, documentationRegistry);
            SubsetScriptTransformer initialTransformer = new SubsetScriptTransformer(initialPassStatementTransformer);
            String id = "cp_" + scriptTarget.getId();
            CompileOperation<PluginRequests> initialOperation = new FactoryBackedCompileOperation<PluginRequests>(id, id, initialTransformer, initialPassStatementTransformer, pluginRequestsSerializer);

            ScriptRunner<? extends BasicScript, PluginRequests> initialRunner = compiler.compile(scriptType, initialOperation, baseScope.getExportClassLoader(), Actions.doNothing());
            initialRunner.run(target, services);

            PluginRequests pluginRequests = initialRunner.getData();
            PluginManagerInternal pluginManager = scriptTarget.getPluginManager();
            pluginRequestApplicator.applyPlugins(pluginRequests, scriptHandler, pluginManager, targetScope);

            // Pass 2, compile everything except buildscript {} and plugin requests, then run
            BuildScriptTransformer buildScriptTransformer = new BuildScriptTransformer(classpathClosureName, scriptSource);
            String operationId = scriptTarget.getId();
            String cacheKey = cacheKey(scriptHandler, scriptTarget, pluginRequests);
            CompileOperation<BuildScriptData> operation = new FactoryBackedCompileOperation<BuildScriptData>(operationId, cacheKey, buildScriptTransformer, buildScriptTransformer, buildScriptDataSerializer);

            final ScriptRunner<? extends BasicScript, BuildScriptData> runner = compiler.compile(scriptType, operation, targetScope.getLocalClassLoader(), ClosureCreationInterceptingVerifier.INSTANCE);
            if (scriptTarget.getSupportsMethodInheritance() && runner.getHasMethods()) {
                scriptTarget.attachScript(runner.getScript());
            }
            if (!runner.getRunDoesSomething()) {
                return;
            }

            Runnable buildScriptRunner = new Runnable() {
                public void run() {
                    runner.run(target, services);
                }
            };

            boolean hasImperativeStatements = runner.getData().getHasImperativeStatements();
            scriptTarget.addConfiguration(buildScriptRunner, !hasImperativeStatements);
        }

        private ScriptTarget wrap(Object target) {
            if (target instanceof ProjectInternal && topLevelScript) {
                // Only use this for top level project scripts
                return new ProjectScriptTarget((ProjectInternal) target);
            }
            if (target instanceof GradleInternal && topLevelScript) {
                // Only use this for top level init scripts
                return new InitScriptTarget((GradleInternal) target);
            }
            if (target instanceof SettingsInternal && topLevelScript) {
                // Only use this for top level settings scripts
                return new SettingScriptTarget((SettingsInternal) target);
            } else {
                return new DefaultScriptTarget(target);
            }
        }

    }

    /**
     * This method tries to build a reasonable operation id for a compilation, based on the script target id, but more
     * importantly the script classpath. It's an approximation, because we will use a hash for the requested plugins
     * as the key, plus the snapshot hash of the build script classpath. If for some reason a plugin changes, we would
     * only be able to realize that the build script classpath part changed: if it's a plugin found in plugin requests,
     * we wouldn't find it. This is however reasonable, because the case were a plugin would change for the same version
     * is likely to be the development of the plugin itself, in which case it's better to use the buildscript { ... }
     * block.
     */
    private String cacheKey(ScriptHandlerInternal handler, ScriptTarget target, PluginRequests plugins) {
        String buildscriptClasspathHash = classPathHash(handler.getScriptClassPath()) + classPathHash(injectedPluginClassPath.getClasspath());
        return target.getId() + hashFor(plugins) + buildscriptClasspathHash;
    }

    private String classPathHash(ClassPath scriptClasspath) {
        return scriptClasspath.isEmpty() ? "" : String.valueOf(classpathSnapshotter.snapshot(scriptClasspath).hashCode());
    }

    // TODO:Cedric instead of using the hash code of plugin request strings, we should really use a ClassPath
    private static String hashFor(PluginRequests plugins) {
        if (plugins.isEmpty()) {
            return "";
        }
        int hash = 0;
        for (PluginRequest plugin : plugins) {
            hash = 31 * hash + plugin.getScriptDisplayName().hashCode();
        }
        return String.valueOf(hash);
    }
}
