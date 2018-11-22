/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.language.cpp.plugins;

import org.gradle.api.Action;
import org.gradle.api.Incubating;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.attributes.Usage;
import org.gradle.api.internal.artifacts.dsl.LazyPublishArtifact;
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.api.internal.file.FileOperations;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.AppliedPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Zip;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.CppLibrary;
import org.gradle.language.cpp.CppPlatform;
import org.gradle.language.cpp.CppSharedLibrary;
import org.gradle.language.cpp.internal.DefaultCppLibrary;
import org.gradle.language.cpp.internal.DefaultUsageContext;
import org.gradle.language.cpp.internal.MainLibraryVariant;
import org.gradle.language.cpp.internal.NativeVariantIdentity;
import org.gradle.language.internal.NativeComponentFactory;
import org.gradle.language.nativeplatform.internal.BinaryBuilder;
import org.gradle.language.nativeplatform.internal.BuildType;
import org.gradle.language.nativeplatform.internal.toolchains.ToolChainSelector;
import org.gradle.nativeplatform.Linkage;
import org.gradle.nativeplatform.MachineArchitecture;
import org.gradle.nativeplatform.OperatingSystemFamily;
import org.gradle.nativeplatform.TargetMachine;
import org.gradle.nativeplatform.TargetMachineFactory;
import org.gradle.nativeplatform.internal.DefaultTargetMachineFactory;
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform;

import javax.inject.Inject;
import java.io.File;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static org.gradle.language.cpp.CppBinary.DEBUGGABLE_ATTRIBUTE;
import static org.gradle.language.cpp.CppBinary.LINKAGE_ATTRIBUTE;
import static org.gradle.language.cpp.CppBinary.OPTIMIZED_ATTRIBUTE;
import static org.gradle.language.nativeplatform.internal.Dimensions.createDimensionSuffix;
import static org.gradle.language.nativeplatform.internal.Dimensions.getDefaultTargetMachines;
import static org.gradle.language.plugins.NativeBasePlugin.setDefaultAndGetTargetMachineValues;
import static org.gradle.nativeplatform.MachineArchitecture.ARCHITECTURE_ATTRIBUTE;
import static org.gradle.nativeplatform.OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE;

/**
 * <p>A plugin that produces a native library from C++ source.</p>
 *
 * <p>Assumes the source files are located in `src/main/cpp`, public headers are located in `src/main/public` and implementation header files are located in `src/main/headers`.</p>
 *
 * <p>Adds a {@link CppLibrary} extension to the project to allow configuration of the library.</p>
 *
 * @since 4.1
 */
@Incubating
public class CppLibraryPlugin implements Plugin<ProjectInternal> {
    private final NativeComponentFactory componentFactory;
    private final ToolChainSelector toolChainSelector;
    private final ImmutableAttributesFactory attributesFactory;
    private final TargetMachineFactory targetMachineFactory;

    /**
     * Injects a {@link FileOperations} instance.
     *
     * @since 4.2
     */
    @Inject
    public CppLibraryPlugin(NativeComponentFactory componentFactory, ToolChainSelector toolChainSelector, ImmutableAttributesFactory attributesFactory, TargetMachineFactory targetMachineFactory) {
        this.componentFactory = componentFactory;
        this.toolChainSelector = toolChainSelector;
        this.attributesFactory = attributesFactory;
        this.targetMachineFactory = targetMachineFactory;
    }

    @Override
    public void apply(final ProjectInternal project) {
        project.getPluginManager().apply(CppBasePlugin.class);

        final TaskContainer tasks = project.getTasks();
        final ObjectFactory objectFactory = project.getObjects();
        final ProviderFactory providers = project.getProviders();

        // Add the library and extension
        final DefaultCppLibrary library = componentFactory.newInstance(CppLibrary.class, DefaultCppLibrary.class, "main");
        project.getExtensions().add(CppLibrary.class, "library", library);
        project.getComponents().add(library);

        // Configure the component
        library.getBaseName().set(project.getName());

        library.getTargetMachines().convention(getDefaultTargetMachines(targetMachineFactory));
        library.getBinaries().whenElementKnown(binary -> {
            if (binary instanceof CppSharedLibrary && !binary.isOptimized()) {
                // Use the debug shared library as the development binary
                library.getDevelopmentBinary().set(binary);
            } else if (!library.getLinkage().get().contains(Linkage.SHARED) && !binary.isOptimized()) {
                // Use the debug static library as the development binary
                library.getDevelopmentBinary().set(binary);
            }
        });

        library.getBinaries().whenElementKnown(binary -> {
            library.getMainPublication().addVariant(binary);
        });

        project.afterEvaluate(new Action<Project>() {
            @Override
            public void execute(final Project project) {
                Set<TargetMachine> targetMachines = setDefaultAndGetTargetMachineValues(library.getTargetMachines(), targetMachineFactory);
                if (targetMachines.isEmpty()) {
                    throw new IllegalArgumentException("A target machine needs to be specified for the library.");
                }

                library.getLinkage().finalizeValue();
                Set<Linkage> linkages = library.getLinkage().get();
                if (linkages.isEmpty()) {
                    throw new IllegalArgumentException("A linkage needs to be specified for the library.");
                }

                for (CppBinary binary : new BinaryBuilder<CppBinary>(project, attributesFactory)
                        .withDimension(
                                BinaryBuilder.newDimension(BuildType.class)
                                        .withValues(BuildType.DEFAULT_BUILD_TYPES)
                                        .attribute(DEBUGGABLE_ATTRIBUTE, it -> it.isDebuggable())
                                        .attribute(OPTIMIZED_ATTRIBUTE, it -> it.isOptimized())
                                        .build())
                        .withDimension(
                                BinaryBuilder.newDimension(Linkage.class)
                                        .withValues(linkages)
                                        .attribute(LINKAGE_ATTRIBUTE, it -> it)
                                        .build())
                        .withDimension(
                                BinaryBuilder.newDimension(TargetMachine.class)
                                        .withValues(targetMachines)
                                        .attribute(OPERATING_SYSTEM_ATTRIBUTE, it -> it.getOperatingSystemFamily())
                                        .attribute(ARCHITECTURE_ATTRIBUTE, it -> it.getArchitecture())
                                        .withName(it -> {
                                            String operatingSystemSuffix = createDimensionSuffix(it.getOperatingSystemFamily(), targetMachines);
                                            String architectureSuffix = createDimensionSuffix(it.getArchitecture(), targetMachines);
                                            return operatingSystemSuffix + architectureSuffix;
                                        })
                                        .build())
                        .withBaseName(library.getBaseName())
                        .withBinaryFactory((NativeVariantIdentity variantIdentity, BinaryBuilder.DimensionContext context) -> {
                            ToolChainSelector.Result<CppPlatform> result = toolChainSelector.select(CppPlatform.class, context.get(TargetMachine.class).get());

                            if (context.get(Linkage.class).get().equals(Linkage.SHARED)) {
                                return library.addSharedLibrary(variantIdentity, result.getTargetPlatform(), result.getToolChain(), result.getPlatformToolProvider());
                            } else if (context.get(Linkage.class).get().equals(Linkage.STATIC)) {
                                return library.addStaticLibrary(variantIdentity, result.getTargetPlatform(), result.getToolChain(), result.getPlatformToolProvider());
                            }
                            throw new IllegalArgumentException("Invalid linkage");
                        })
                        .build()
                        .get()) {
                    library.getBinaries().add(binary);
                }

                Usage runtimeUsage = objectFactory.named(Usage.class, Usage.NATIVE_RUNTIME);
                Usage linkUsage = objectFactory.named(Usage.class, Usage.NATIVE_LINK);

                for (BuildType buildType : BuildType.DEFAULT_BUILD_TYPES) {
                    for (TargetMachine targetMachine : targetMachines) {
                        for (Linkage linkage : linkages) {

                            String operatingSystemSuffix = createDimensionSuffix(targetMachine.getOperatingSystemFamily(), targetMachines.stream().map(TargetMachine::getOperatingSystemFamily).collect(Collectors.toSet()));
                            String architectureSuffix = createDimensionSuffix(targetMachine.getArchitecture(), targetMachines.stream().map(TargetMachine::getArchitecture).collect(Collectors.toSet()));
                            String linkageSuffix = createDimensionSuffix(linkage, linkages);
                            String variantName = buildType.getName() + linkageSuffix + operatingSystemSuffix + architectureSuffix;

                            Provider<String> group = project.provider(new Callable<String>() {
                                @Override
                                public String call() throws Exception {
                                    return project.getGroup().toString();
                                }
                            });

                            Provider<String> version = project.provider(new Callable<String>() {
                                @Override
                                public String call() throws Exception {
                                    return project.getVersion().toString();
                                }
                            });

                            AttributeContainer runtimeAttributes = attributesFactory.mutable();
                            runtimeAttributes.attribute(Usage.USAGE_ATTRIBUTE, runtimeUsage);
                            runtimeAttributes.attribute(DEBUGGABLE_ATTRIBUTE, buildType.isDebuggable());
                            runtimeAttributes.attribute(OPTIMIZED_ATTRIBUTE, buildType.isOptimized());
                            runtimeAttributes.attribute(LINKAGE_ATTRIBUTE, linkage);
                            runtimeAttributes.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, targetMachine.getOperatingSystemFamily());
                            runtimeAttributes.attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE, targetMachine.getArchitecture());

                            AttributeContainer linkAttributes = attributesFactory.mutable();
                            linkAttributes.attribute(Usage.USAGE_ATTRIBUTE, linkUsage);
                            linkAttributes.attribute(DEBUGGABLE_ATTRIBUTE, buildType.isDebuggable());
                            linkAttributes.attribute(OPTIMIZED_ATTRIBUTE, buildType.isOptimized());
                            linkAttributes.attribute(LINKAGE_ATTRIBUTE, linkage);
                            linkAttributes.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, targetMachine.getOperatingSystemFamily());
                            linkAttributes.attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE, targetMachine.getArchitecture());

                            NativeVariantIdentity variantIdentity = new NativeVariantIdentity(variantName, library.getBaseName(), group, version, buildType.isDebuggable(), buildType.isOptimized(), targetMachine,
                                new DefaultUsageContext(variantName + "Link", linkUsage, linkAttributes),
                                new DefaultUsageContext(variantName + "Runtime", runtimeUsage, runtimeAttributes));

                            if (DefaultNativePlatform.getCurrentOperatingSystem().toFamilyName().equals(targetMachine.getOperatingSystemFamily().getName())) {
                                // Do nothing...
                            } else {
                                // Known, but not buildable
                                library.getMainPublication().addVariant(variantIdentity);
                            }
                        }
                    }
                }

                final MainLibraryVariant mainVariant = library.getMainPublication();

                final Configuration apiElements = library.getApiElements();
                // TODO - deal with more than one header dir, e.g. generated public headers
                Provider<File> publicHeaders = providers.provider(new Callable<File>() {
                    @Override
                    public File call() throws Exception {
                        Set<File> files = library.getPublicHeaderDirs().getFiles();
                        if (files.size() != 1) {
                            throw new UnsupportedOperationException(String.format("The C++ library plugin currently requires exactly one public header directory, however there are %d directories configured: %s", files.size(), files));
                        }
                        return files.iterator().next();
                    }
                });
                apiElements.getOutgoing().artifact(publicHeaders);

                project.getPluginManager().withPlugin("maven-publish", new Action<AppliedPlugin>() {
                    @Override
                    public void execute(AppliedPlugin appliedPlugin) {
                        final TaskProvider<Zip> headersZip = tasks.register("cppHeaders", Zip.class, new Action<Zip>() {
                            @Override
                            public void execute(Zip headersZip) {
                                headersZip.from(library.getPublicHeaderFiles());
                                headersZip.getDestinationDirectory().set(project.getLayout().getBuildDirectory().dir("headers"));
                                headersZip.getArchiveClassifier().set("cpp-api-headers");
                                headersZip.getArchiveFileName().set("cpp-api-headers.zip");
                            }
                        });
                        mainVariant.addArtifact(new LazyPublishArtifact(headersZip));
                    }
                });

                library.getBinaries().realizeNow();
            }
        });
    }

    private boolean shouldPrefer(BuildType buildType, TargetMachine targetMachine, CppLibrary library) {
        return buildType == BuildType.DEBUG && (targetMachine.getArchitecture().equals(((DefaultTargetMachineFactory)targetMachineFactory).host().getArchitecture()) || !library.getDevelopmentBinary().isPresent());
    }
}
