/*
 * Copyright 2006-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.citrusframework.remote.plugin;

import org.apache.maven.archiver.MavenArchiveConfiguration;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.assembly.AssemblerConfigurationSource;
import org.apache.maven.plugins.assembly.InvalidAssemblerConfigurationException;
import org.apache.maven.plugins.assembly.archive.ArchiveCreationException;
import org.apache.maven.plugins.assembly.archive.AssemblyArchiver;
import org.apache.maven.plugins.assembly.format.AssemblyFormattingException;
import org.apache.maven.plugins.assembly.io.AssemblyReadException;
import org.apache.maven.plugins.assembly.io.AssemblyReader;
import org.apache.maven.plugins.assembly.model.Assembly;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.shared.filtering.MavenReaderFilter;
import org.citrusframework.remote.plugin.assembly.CitrusRemoteAssemblerConfigurationSource;
import org.citrusframework.remote.plugin.config.AssemblyConfiguration;
import org.citrusframework.remote.plugin.config.AssemblyDescriptorConfiguration;
import org.citrusframework.remote.plugin.config.TestJarConfiguration;

import java.io.File;
import java.util.List;
import java.util.Optional;

/**
 * @author Christoph Deppisch
 * @since 2.7.4
 */
public abstract class AbstractCitrusRemoteAssemblyMojo extends AbstractCitrusRemoteMojo {

    @Parameter
    protected AssemblyConfiguration assemblyConfiguration;

    @Parameter
    protected TestJarConfiguration testJar;

    @Component
    private AssemblyArchiver assemblyArchiver;

    @Component
    private AssemblyReader assemblyReader;

    @Component
    private MavenReaderFilter readerFilter;

    @Component
    protected MavenProjectHelper projectHelper;

    @Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true)
    private List<MavenProject> reactorProjects;

    @Parameter( defaultValue = "${project.build.finalName}", required = true)
    private String finalName;

    @Parameter(defaultValue = "${project.build.testOutputDirectory}", required = true, readonly = true)
    private File testClassesDirectory;

    @Parameter( property = "citrus.remote.mainClass", required = true, defaultValue = "org.citrusframework.remote.CitrusRemoteServer")
    private String mainClass;

    /**
     * Directory to unpack JARs into if needed
     */
    @Parameter(defaultValue = "${project.build.directory}/assembly/work", readonly = true, required = true)
    private File workingDirectory;

    /**
     * Temporary directory that contain the files to be assembled.
     */
    @Parameter(defaultValue = "${project.build.directory}/assembly/tmp", readonly = true, required = true)
    private File temporaryRootDirectory;

    @Override
    public void doExecute() throws MojoExecutionException, MojoFailureException {
        initializeAssembly();
        createDirs(assemblyConfiguration);
        createAssemblyArchive(assemblyConfiguration);
    }

    protected void createDirs(AssemblyConfiguration assemblyConfig) {
        for (File dir : new File[] { assemblyConfig.getTemporaryRootDirectory(), assemblyConfig.getOutputDirectory(), assemblyConfig.getWorkingDirectory() }) {
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalArgumentException("Cannot create directory " + dir.getAbsolutePath());
            }
        }
    }

    private void initializeAssembly() {
        if (!hasAssemblyConfiguration()) {
            assemblyConfiguration = Optional.ofNullable(assemblyConfiguration).orElse(new AssemblyConfiguration());
            AssemblyDescriptorConfiguration descriptorConfiguration = new AssemblyDescriptorConfiguration();
            descriptorConfiguration.setRef(getDefaultDescriptorRef());
            assemblyConfiguration.setDescriptor(descriptorConfiguration);
        }

        assemblyConfiguration.setOutputDirectory(getOutputDirectory());
        assemblyConfiguration.setWorkingDirectory(workingDirectory);
        assemblyConfiguration.setTemporaryRootDirectory(temporaryRootDirectory);

        if (assemblyConfiguration.getArchive() == null) {
            assemblyConfiguration.setArchive(new MavenArchiveConfiguration());
        }

        if (assemblyConfiguration.getArchive().getManifest().getMainClass() == null){
            assemblyConfiguration.getArchive().getManifest().setMainClass(mainClass);
        }
    }

    /**
     * Subclasses provide default descriptor reference.
     * @return
     */
    protected abstract String getDefaultDescriptorRef();

    protected boolean hasAssemblyConfiguration() {
        return assemblyConfiguration != null && assemblyConfiguration.getDescriptor() != null &&
                (assemblyConfiguration.getDescriptor().getInline() != null ||
                        assemblyConfiguration.getDescriptor().getFile() != null ||
                        assemblyConfiguration.getDescriptor().getRef() != null);
    }

    protected void createAssemblyArchive(AssemblyConfiguration assemblyConfig) throws MojoExecutionException {
        CitrusRemoteAssemblerConfigurationSource source = new CitrusRemoteAssemblerConfigurationSource(assemblyConfig, project, session, readerFilter, reactorProjects);
        Assembly assembly = getAssemblyConfig(assemblyConfig, source);

        try {
            for (String format : assembly.getFormats()) {
                assemblyArchiver.createArchive(assembly, finalName + "-" + assembly.getId(), format, source, false, "merge");
            }
        } catch (ArchiveCreationException | AssemblyFormattingException e) {
            throw new MojoExecutionException("Failed to create assembly for test jar", e);
        } catch (InvalidAssemblerConfigurationException e) {
            throw new MojoExecutionException("Invalid assembly descriptor: " + assembly.getId(), e);
        }
    }

    private Assembly getAssemblyConfig(AssemblyConfiguration assemblyConfig, CitrusRemoteAssemblerConfigurationSource source) throws MojoExecutionException {
        Assembly assembly = assemblyConfig.getDescriptor().getInline();
        if (assembly == null) {
            assembly = extractAssembly(source);
        }
        return assembly;
    }

    private Assembly extractAssembly(AssemblerConfigurationSource config) throws MojoExecutionException {
        try {
            List<Assembly> assemblies = assemblyReader.readAssemblies(config);
            if (assemblies.size() != 1) {
                throw new MojoExecutionException(String.format("Multiple assemblies not supported - found %s assemblies", assemblies.size()));
            }
            return assemblies.get(0);
        } catch (AssemblyReadException e) {
            throw new MojoExecutionException("Error reading assembly: " + e.getMessage(), e);
        } catch (InvalidAssemblerConfigurationException e) {
            throw new MojoExecutionException(assemblyReader, e.getMessage(), "Assembly configuration is invalid: " + e.getMessage());
        }
    }

    /**
     * Sets the assembly.
     *
     * @param assemblyConfiguration
     */
    public void setAssemblyConfiguration(AssemblyConfiguration assemblyConfiguration) {
        this.assemblyConfiguration = assemblyConfiguration;
    }

    /**
     * Gets the assembly.
     *
     * @return
     */
    public AssemblyConfiguration getAssemblyConfiguration() {
        if (!hasAssemblyConfiguration()) {
            initializeAssembly();
        }

        return assemblyConfiguration;
    }

    /**
     * Sets the testJar.
     *
     * @param testJar
     */
    public void setTestJar(TestJarConfiguration testJar) {
        this.testJar = testJar;
    }

    /**
     * Gets the testJar.
     *
     * @return
     */
    public TestJarConfiguration getTestJar() {
        if (testJar == null) {
            testJar = new TestJarConfiguration();
            testJar.setClassifier("tests");
            testJar.setTestClassesDirectory(testClassesDirectory);
        }

        return testJar;
    }

    /**
     * Gets the finalName.
     *
     * @return
     */
    public String getFinalName() {
        return finalName;
    }
}
