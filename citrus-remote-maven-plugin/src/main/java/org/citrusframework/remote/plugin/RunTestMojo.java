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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.citrusframework.TestClass;
import org.citrusframework.TestResult;
import org.citrusframework.TestSource;
import org.citrusframework.exceptions.CitrusRuntimeException;
import org.citrusframework.main.TestRunConfiguration;
import org.citrusframework.remote.model.RemoteResult;
import org.citrusframework.remote.plugin.config.RunConfiguration;
import org.citrusframework.report.*;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;

/**
 * @author Christoph Deppisch
 * @since 2.7.4
 */
@Mojo(name = "test", defaultPhase = LifecyclePhase.INTEGRATION_TEST, requiresDependencyResolution = ResolutionScope.TEST)
public class RunTestMojo extends AbstractCitrusRemoteMojo {

    /** Global url encoding */
    private static final String ENCODING = "UTF-8";

    @Parameter(property = "citrus.remote.skip.test", defaultValue = "false")
    protected boolean skipRun;

    /**
     * Run configuration for test execution on remote server.
     */
    @Parameter
    private RunConfiguration run;

    /**
     * Object mapper for JSON response to object conversion.
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static String parseResultToStringRepresentation(TestResult result) {
        if (result.isSkipped()) {
            return "x";
        } else if (result.isSuccess()) {
            return "+";
        }

        return "-";
    }

    @Override
    public void doExecute() throws MojoExecutionException, MojoFailureException {
        if (skipRun) {
            return;
        }

        if (run == null) {
            run = new RunConfiguration();
        }

        if (!run.hasClasses() && !run.hasPackages()) {
            runAllTests();
        }

        if (run.hasClasses()) {
            runClasses(run.getClasses());
        }

        if (run.hasPackages()) {
            runPackages(run.getPackages());
        }
    }

    private void runPackages(List<String> packages) throws MojoExecutionException {
        TestRunConfiguration runConfiguration = new TestRunConfiguration();

        runConfiguration.setEngine(run.getEngine());
        runConfiguration.setPackages(packages);

        if (run.getIncludes() != null) {
            runConfiguration.setIncludes(run.getIncludes().toArray(new String[0]));
        }

        if (run.getSystemProperties() != null) {
            runConfiguration.addDefaultProperties(run.getSystemProperties());
        }

        runTests(runConfiguration);
    }

    private void runClasses(List<String> classes) throws MojoExecutionException {
        TestRunConfiguration runConfiguration = new TestRunConfiguration();

        runConfiguration.setEngine(run.getEngine());

        List<TestSource> testSources = classes.stream()
                .map(TestClass::fromString)
                .map(testClass -> (TestSource) testClass)
                .toList();
        runConfiguration.setTestSources(testSources);

        if (run.getSystemProperties() != null) {
            runConfiguration.addDefaultProperties(run.getSystemProperties());
        }

        runTests(runConfiguration);
    }

    private void runAllTests() throws MojoExecutionException {
        TestRunConfiguration runConfiguration = new TestRunConfiguration();

        runConfiguration.setEngine(run.getEngine());
        if (run.getIncludes() != null) {
            runConfiguration.setIncludes(run.getIncludes().toArray(new String[0]));
        }

        if (run.getSystemProperties() != null) {
            runConfiguration.addDefaultProperties(run.getSystemProperties());
        }

        runTests(runConfiguration);
    }

    /**
     * Invokes run tests remote service and provide response message. If async mode is used the service is called with request method PUT
     * that creates a new run job on the server. The test results are then polled with multiple requests instead of processing the single synchronous response.
     *
     * @param runConfiguration
     * @throws MojoExecutionException
     */
    private void runTests(TestRunConfiguration runConfiguration) throws MojoExecutionException {
        try {
            ClassicRequestBuilder requestBuilder;

            if (run.isAsync()) {
                requestBuilder = ClassicRequestBuilder.put(getServer().getUrl() + "/run");
            } else {
                requestBuilder = ClassicRequestBuilder.post(getServer().getUrl() + "/run");
            }

            requestBuilder.addHeader(new BasicHeader(HttpHeaders.ACCEPT, ContentType.APPLICATION_JSON.getMimeType()));

            StringEntity body = new StringEntity(new ObjectMapper().writeValueAsString(runConfiguration), ContentType.APPLICATION_JSON);
            requestBuilder.setEntity(body);

            try (var response = getHttpClient().execute(requestBuilder.build(), classicHttpResponse -> classicHttpResponse)) {
                if (HttpStatus.SC_OK != response.getCode()) {
                    throw new MojoExecutionException("Failed to run tests on remote server: " + EntityUtils.toString(response.getEntity()));
                }

                if (run.isAsync()) {
                    handleTestResults(pollTestResults());
                } else {
                    handleTestResults(objectMapper.readValue(response.getEntity().getContent(), RemoteResult[].class));
                }
            }
        } catch (IOException | ParseException e) {
            throw new MojoExecutionException("Failed to run tests on remote server", e);
        }
    }

    /**
     * When using async test execution mode the client does not synchronously wait for test results as it might lead to read timeouts. Instead
     * this method polls for test results and waits for the test execution to completely finish.
     *
     * @throws MojoExecutionException
     */
    private RemoteResult[] pollTestResults() throws MojoExecutionException, IOException {
        ClassicHttpResponse response = null;
        try {
            do {
                if (response != null) {
                    response.close();
                }

                ClassicHttpRequest httpRequest = ClassicRequestBuilder.get(getServer().getUrl() + "/results")
                        .addHeader(new BasicHeader(HttpHeaders.ACCEPT, ContentType.APPLICATION_JSON.getMimeType()))
                        .addParameter("timeout", String.valueOf(run.getPollingInterval()))
                        .build();
                response = getHttpClient().execute(httpRequest, classicHttpResponse -> classicHttpResponse);

                if (HttpStatus.SC_PARTIAL_CONTENT == response.getCode()) {
                    getLog().info("Waiting for remote tests to finish ...");
                    getLog().info(Stream.of(objectMapper.readValue(response.getEntity().getContent(), RemoteResult[].class))
                            .map(RemoteResult::toTestResult)
                            .map(RunTestMojo::parseResultToStringRepresentation)
                            .collect(joining()));
                }
            } while (HttpStatus.SC_PARTIAL_CONTENT == response.getCode());

            if (HttpStatus.SC_OK != response.getCode()) {
                throw new MojoExecutionException("Failed to get test results from remote server: " + EntityUtils.toString(response.getEntity()));
            }

            return objectMapper.readValue(response.getEntity().getContent(), RemoteResult[].class);
        } catch (IOException | ParseException e) {
            throw new MojoExecutionException("Failed to get test results from remote server", e);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    /**
     * Check test results for failures.
     * @param results
     * @throws IOException
     */
    private void handleTestResults(RemoteResult[] results) throws IOException {
        StringWriter resultWriter = new StringWriter();
        resultWriter.append(String.format("%n"));

        TestResults testResults = new TestResults();
        Arrays.stream(results).forEach(remoteResult -> testResults.addResult(RemoteResult.toTestResult(remoteResult)));

        OutputStreamReporter reporter = new OutputStreamReporter(resultWriter);
        reporter.generate(testResults);
        getLog().info(resultWriter.toString());

        if (getReport().isHtmlReport()) {
            HtmlReporter htmlReporter = new HtmlReporter();
            htmlReporter.setReportDirectory(getOutputDirectory().getPath() + File.separator + getReport().getDirectory());
            htmlReporter.generate(testResults);
        }

        SummaryReporter summaryReporter = new SummaryReporter();
        summaryReporter.setReportDirectory(getOutputDirectory().getPath() + File.separator + getReport().getDirectory());
        summaryReporter.setReportFileName(getReport().getSummaryFile());
        summaryReporter.generate(testResults);

        getAndSaveReports();
    }

    private void getAndSaveReports() throws IOException {
        if (!getReport().isSaveReportFiles()) {
            return;
        }

        ClassicHttpRequest httpRequest = ClassicRequestBuilder.get(getServer().getUrl() + "/results/files")
                .addHeader(new BasicHeader(HttpHeaders.ACCEPT, ContentType.APPLICATION_XML.getMimeType()))
                .build();

        String[] reportFiles = {};
        try (var response = getHttpClient().execute(httpRequest, classicHttpResponse -> classicHttpResponse)){
            if (HttpStatus.SC_OK != response.getCode()) {
                getLog().warn("Failed to get test reports from remote server");
            }

            reportFiles = objectMapper.readValue(response.getEntity().getContent(), String[].class);
        } catch (IOException e) {
            getLog().warn("Failed to get test reports from remote server", e);
        }

        File citrusReportsDirectory = new File(getOutputDirectory() + File.separator + getReport().getDirectory());
        if (!citrusReportsDirectory.exists()&& !citrusReportsDirectory.mkdirs()) {
            throw new CitrusRuntimeException("Unable to create reports output directory: " + citrusReportsDirectory.getPath());
        }

        File junitReportsDirectory = new File(citrusReportsDirectory, "junitreports");
        if (!junitReportsDirectory.exists() && !junitReportsDirectory.mkdirs()) {
            throw new CitrusRuntimeException("Unable to create JUnit reports directory: " + junitReportsDirectory.getPath());
        }

        JUnitReporter jUnitReporter = new JUnitReporter();
        loadAndSaveReportFile(new File(citrusReportsDirectory, String.format(jUnitReporter.getReportFileNamePattern(), jUnitReporter.getSuiteName())), getServer().getUrl() + "/results/suite", ContentType.APPLICATION_XML.getMimeType());

        Stream.of(reportFiles)
            .map(reportFile -> new File(junitReportsDirectory, reportFile))
            .forEach(reportFile -> {
                try {
                    loadAndSaveReportFile(reportFile, getServer().getUrl() + "/results/file/" + URLEncoder.encode(reportFile.getName(), ENCODING), ContentType.APPLICATION_XML.getMimeType());
                } catch (IOException e) {
                    getLog().warn("Failed to get report file: " + reportFile.getName(), e);
                }
            });
    }

    /**
     * Get report file content from server and save content to given file on local file system.
     * @param reportFile
     * @param serverUrl
     * @param contentType
     */
    private void loadAndSaveReportFile(File reportFile, String serverUrl, String contentType) {
        ClassicHttpRequest httpRequest = ClassicRequestBuilder.get(serverUrl)
                .addHeader(new BasicHeader(HttpHeaders.ACCEPT, contentType))
                .build();

        try (var fileResponse = getHttpClient().execute(httpRequest, classicHttpResponse -> classicHttpResponse)) {
            if (HttpStatus.SC_OK != fileResponse.getCode()) {
                getLog().warn("Failed to get report file: " + reportFile.getName());
                return;
            }

            getLog().info("Writing report file: " + reportFile);
            Files.write(reportFile.toPath(), fileResponse.getEntity().getContent().readAllBytes(), StandardOpenOption.CREATE);
        } catch (IOException e) {
            getLog().warn("Failed to get report file: " + reportFile.getName(), e);
        }
    }

    /**
     * Sets the tests.
     *
     * @param tests
     */
    public void setTests(RunConfiguration tests) {
        this.run = tests;
    }
}
