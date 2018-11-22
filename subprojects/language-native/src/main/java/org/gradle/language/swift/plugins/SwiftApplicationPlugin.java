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

package org.gradle.language.swift.plugins;

import org.gradle.api.Action;
import org.gradle.api.Incubating;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.api.internal.file.FileOperations;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.language.cpp.internal.NativeVariantIdentity;
import org.gradle.language.internal.NativeComponentFactory;
import org.gradle.language.nativeplatform.internal.BinaryBuilder;
import org.gradle.language.nativeplatform.internal.BuildType;
import org.gradle.language.nativeplatform.internal.Dimensions;
import org.gradle.language.nativeplatform.internal.toolchains.ToolChainSelector;
import org.gradle.language.swift.SwiftApplication;
import org.gradle.language.swift.SwiftExecutable;
import org.gradle.language.swift.SwiftPlatform;
import org.gradle.language.swift.internal.DefaultSwiftApplication;
import org.gradle.nativeplatform.TargetMachine;
import org.gradle.nativeplatform.TargetMachineFactory;
import org.gradle.util.GUtil;

import javax.inject.Inject;
import java.util.Set;

import static org.gradle.language.cpp.CppBinary.DEBUGGABLE_ATTRIBUTE;
import static org.gradle.language.cpp.CppBinary.OPTIMIZED_ATTRIBUTE;
import static org.gradle.language.nativeplatform.internal.Dimensions.createDimensionSuffix;
import static org.gradle.language.plugins.NativeBasePlugin.setDefaultAndGetTargetMachineValues;
import static org.gradle.nativeplatform.MachineArchitecture.ARCHITECTURE_ATTRIBUTE;
import static org.gradle.nativeplatform.OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE;

/**
 * <p>A plugin that produces an executable from Swift source.</p>
 *
 * <p>Adds compile, link and install tasks to build the executable. Defaults to looking for source files in `src/main/swift`.</p>
 *
 * <p>Adds a {@link SwiftApplication} extension to the project to allow configuration of the executable.</p>
 *
 * @since 4.5
 */
@Incubating
public class SwiftApplicationPlugin implements Plugin<ProjectInternal> {
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
    public SwiftApplicationPlugin(NativeComponentFactory componentFactory, ToolChainSelector toolChainSelector, ImmutableAttributesFactory attributesFactory, TargetMachineFactory targetMachineFactory) {
        this.componentFactory = componentFactory;
        this.toolChainSelector = toolChainSelector;
        this.attributesFactory = attributesFactory;
        this.targetMachineFactory = targetMachineFactory;
    }

    @Override
    public void apply(final ProjectInternal project) {
        project.getPluginManager().apply(SwiftBasePlugin.class);

        final ConfigurationContainer configurations = project.getConfigurations();

        // Add the application and extension
        final DefaultSwiftApplication application = componentFactory.newInstance(SwiftApplication.class, DefaultSwiftApplication.class, "main");
        project.getExtensions().add(SwiftApplication.class, "application", application);
        project.getComponents().add(application);

        // Setup component
        application.getModule().set(GUtil.toCamelCase(project.getName()));

        application.getTargetMachines().convention(Dimensions.getDefaultTargetMachines(targetMachineFactory));
        application.getBinaries().whenElementKnown(SwiftExecutable.class, executable -> {
            // Use the debug variant as the development binary
            if (!executable.isOptimized()) {
                application.getDevelopmentBinary().set(executable);
            }
        });

        project.afterEvaluate(new Action<Project>() {
            @Override
            public void execute(final Project project) {
                Set<TargetMachine> targetMachines = setDefaultAndGetTargetMachineValues(application.getTargetMachines(), targetMachineFactory);
                if (targetMachines.isEmpty()) {
                    throw new IllegalArgumentException("A target machine needs to be specified for the application.");
                }

                for (SwiftExecutable binary : new BinaryBuilder<SwiftExecutable>(project, attributesFactory)
                        .withDimension(
                                BinaryBuilder.newDimension(BuildType.class)
                                        .withValues(BuildType.DEFAULT_BUILD_TYPES)
                                        .attribute(DEBUGGABLE_ATTRIBUTE, it -> it.isDebuggable())
                                        .attribute(OPTIMIZED_ATTRIBUTE, it -> it.isOptimized())
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
                        .withBaseName(application.getModule())
                        .withBinaryFactory((NativeVariantIdentity variantIdentity, BinaryBuilder.DimensionContext context) -> {
                            ToolChainSelector.Result<SwiftPlatform> result = toolChainSelector.select(SwiftPlatform.class, context.get(TargetMachine.class).get());
                            return application.addExecutable(variantIdentity, context.get(BuildType.class).get() == BuildType.DEBUG, result.getTargetPlatform(), result.getToolChain(), result.getPlatformToolProvider());
                        })
                        .build()
                        .get()) {
                    application.getBinaries().add(binary);
                }
                // Configure the binaries
                application.getBinaries().realizeNow();
            }
        });
    }
}
