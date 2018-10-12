package com.github.spotbugs;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Incubating;
import org.gradle.api.JavaVersion;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.reporting.Reporting;
import org.gradle.api.reporting.SingleFileReport;
import org.gradle.api.resources.TextResource;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceTask;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.VerificationTask;
import org.gradle.internal.logging.ConsoleRenderer;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.process.internal.worker.WorkerProcessFactory;
import org.gradle.util.ConfigureUtil;

import com.github.spotbugs.internal.SpotBugsReportsImpl;
import com.github.spotbugs.internal.SpotBugsReportsInternal;
import com.github.spotbugs.internal.spotbugs.SpotBugsClasspathValidator;
import com.github.spotbugs.internal.spotbugs.SpotBugsResult;
import com.github.spotbugs.internal.spotbugs.SpotBugsSpec;
import com.github.spotbugs.internal.spotbugs.SpotBugsSpecBuilder;
import com.github.spotbugs.internal.spotbugs.SpotBugsWorkerManager;

import groovy.lang.Closure;

/**
 * Analyzes code with <a href="https://spotbugs.github.io/">SpotBugs</a>. See the
 * <a href="https://spotbugs.readthedocs.io/en/latest/">SpotBugs Manual</a> for additional
 * information on configuration options.
 */
@CacheableTask
public class SpotBugsTask extends SourceTask implements VerificationTask, Reporting<SpotBugsReports> {
    private FileCollection classes;

    private FileCollection classpath;

    private Set<File> sourceDirs;

    private FileCollection spotbugsClasspath;

    private FileCollection pluginClasspath;

    private boolean ignoreFailures;

    private boolean showProgress;

    private String effort;

    private String reportLevel;

    private String maxHeapSize;

    private Collection<String> visitors = new ArrayList<>();

    private Collection<String> omitVisitors = new ArrayList<>();

    private TextResource includeFilterConfig;

    private TextResource excludeFilterConfig;

    private TextResource excludeBugsFilterConfig;

    private Collection<String> extraArgs = new ArrayList<>();

    private Collection<String> jvmArgs = new ArrayList<>();

    private Map<String, Object> systemProperties = new HashMap<>();

    @Nested
    private final SpotBugsReportsInternal reports;

    public SpotBugsTask() {
        reports = getInstantiator().newInstance(SpotBugsReportsImpl.class, this);
    }

    @Inject
    public Instantiator getInstantiator() {
        throw new UnsupportedOperationException();
    }

    @Inject
    public WorkerProcessFactory getWorkerProcessBuilderFactory() {
        throw new UnsupportedOperationException();
    }

    /**
     * The reports to be generated by this task.
     *
     * @return The reports container
     */
    @Override
    public SpotBugsReports getReports() {
        return reports;
    }

    /**
     * Configures the reports to be generated by this task.
     *
     * The contained reports can be configured by name and closures. Example:
     *
     * <pre>
     * spotbugsTask {
     *   reports {
     *     xml {
     *       destination "build/spotbugs.xml"
     *     }
     *   }
     * }
     * </pre>
     *
     * @param closure The configuration
     * @return The reports container
     */
    @Override
    public SpotBugsReports reports(Closure closure) {
        return reports(ConfigureUtil.configureUsing(closure));
    }

    /**
     * Configures the reports to be generated by this task.
     *
     * The contained reports can be configured by name and closures. Example:
     *
     * <pre>
     * spotbugsTask {
     *   reports {
     *     xml {
     *       destination "build/spotbugs.xml"
     *     }
     *   }
     * }
     * </pre>
     *
     *
     * @param configureAction The configuration
     * @return The reports container
     */
    @Override
    public SpotBugsReports reports(Action<? super SpotBugsReports> configureAction) {
        configureAction.execute(reports);
        return reports;
    }

    /**
     * The filename of a filter specifying which bugs are reported.
     *
     * @return filename of a filter specifying which bugs are reported
     */
    @Internal
    public File getIncludeFilter() {
        TextResource config = getIncludeFilterConfig();
        return config == null ? null : config.asFile();
    }

    /**
     * The filename of a filter specifying which bugs are reported.
     *
     * @param filter
     *            filename of a filter specifying which bugs are report
     */
    public void setIncludeFilter(File filter) {
        setIncludeFilterConfig(getProject().getResources().getText().fromFile(filter));
    }

    /**
     * The filename of a filter specifying bugs to exclude from being reported.
     *
     * @return filename of a filter specifying bugs to exclude from being reported
     */
    @Internal
    public File getExcludeFilter() {
        TextResource config = getExcludeFilterConfig();
        return config == null ? null : config.asFile();
    }

    /**
     * The filename of a filter specifying bugs to exclude from being reported.
     *
     * @param filter
     *            filename of a filter specifying bugs to exclude from being reported
     */
    public void setExcludeFilter(File filter) {
        setExcludeFilterConfig(getProject().getResources().getText().fromFile(filter));
    }

    /**
     * The filename of a filter specifying baseline bugs to exclude from being reported.
     *
     * @return filename of a filter specifying baseline bugs to exclude from being reported
     */
    @Internal
    public File getExcludeBugsFilter() {
        TextResource config = getExcludeBugsFilterConfig();
        return config == null ? null : config.asFile();
    }

    /**
     * The filename of a filter specifying baseline bugs to exclude from being reported.
     *
     * @param filter
     *            filename of a filter specifying baseline bugs to exclude from being reported
     */
    public void setExcludeBugsFilter(File filter) {
        setExcludeBugsFilterConfig(getProject().getResources().getText().fromFile(filter));
    }

    @TaskAction
    public void run() throws IOException, InterruptedException {
        new SpotBugsClasspathValidator(JavaVersion.current()).validateClasspath(
                getSpotbugsClasspath().getFiles().stream().map(File::getName).collect(Collectors.toSet()));
        SpotBugsSpec spec = generateSpec();
        SpotBugsWorkerManager manager = new SpotBugsWorkerManager();

        getLogging().captureStandardOutput(LogLevel.DEBUG);
        getLogging().captureStandardError(LogLevel.DEBUG);

        SpotBugsResult result = manager.runWorker(getProject().getProjectDir(), getWorkerProcessBuilderFactory(), getSpotbugsClasspath(), spec);
        evaluateResult(result);
    }

    SpotBugsSpec generateSpec() {
        SpotBugsSpecBuilder specBuilder = new SpotBugsSpecBuilder(getClasses())
                .withPluginsList(getPluginClasspath())
                .withSources(getAllSource())
                .withClasspath(getClasspath())
                .withShowProgress(getShowProgress())
                .withDebugging(getLogger().isDebugEnabled())
                .withEffort(getEffort())
                .withReportLevel(getReportLevel())
                .withMaxHeapSize(getMaxHeapSize())
                .withVisitors(getVisitors())
                .withOmitVisitors(getOmitVisitors())
                .withExcludeFilter(getExcludeFilter())
                .withIncludeFilter(getIncludeFilter())
                .withExcludeBugsFilter(getExcludeBugsFilter())
                .withExtraArgs(getExtraArgs())
                .withJvmArgs(getJvmArgs())
                .withSystemProperties(getSystemProperties())
                .configureReports(getReports());

        return specBuilder.build();
    }

    void evaluateResult(SpotBugsResult result) {
        if (result.getException() != null) {
            throw new GradleException("SpotBugs encountered an error. Run with --debug to get more information.", result.getException());
        }

        if (result.getErrorCount() > 0) {
            throw new GradleException("SpotBugs encountered an error. Run with --debug to get more information.");
        }

        if (result.getBugCount() > 0) {
            String message = "SpotBugs rule violations were found.";
            SingleFileReport report = reports.getFirstEnabled();
            if (report != null) {
                String reportUrl = new ConsoleRenderer().asClickableFileUrl(report.getDestination());
                message += " See the report at: " + reportUrl;
            }

            if (getIgnoreFailures()) {
                getLogger().warn(message);
            } else {
                throw new GradleException(message);
            }

        }

    }

    public SpotBugsTask extraArgs(Iterable<String> arguments) {
        for (String argument : arguments) {
            extraArgs.add(argument);
        }

        return this;
    }

    public SpotBugsTask extraArgs(String... arguments) {
        extraArgs.addAll(Arrays.asList(arguments));
        return this;
    }

    public SpotBugsTask jvmArgs(Iterable<String> arguments) {
        for (String argument : arguments) {
            jvmArgs.add(argument);
        }

        return this;
    }

    public SpotBugsTask jvmArgs(String... arguments) {
        jvmArgs.addAll(Arrays.asList(arguments));
        return this;
    }

    public SpotBugsTask systemProperty(String name, Object argument) {
        systemProperties.put(name, argument);
        return this;
    }

    public SpotBugsTask systemProperties(Map<String, Object> arguments) {
        systemProperties.putAll(arguments);
        return this;
    }

    SpotBugsTask setSourceSet(SourceSet sourceSet) {
        this.sourceDirs = sourceSet.getAllJava().getSrcDirs();
        setSource(sourceDirs);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PathSensitive(PathSensitivity.RELATIVE)
    public FileTree getSource() {
        return super.getSource();
    }

    @Input
    @PathSensitive(PathSensitivity.RELATIVE)
    FileCollection getAllSource() {
        return getProject().files(sourceDirs).plus(getSource());
    }

    /**
     * The classes to be analyzed.
     *
     * @return classes to be analyzed
     */
    @SkipWhenEmpty
    @PathSensitive(PathSensitivity.RELATIVE)
    @InputFiles
    public FileCollection getClasses() {
        return classes;
    }

    /**
     * @param classes
     *            classes to be analyzed
     */
    public void setClasses(FileCollection classes) {
        this.classes = classes;
    }

    /**
     * Compile class path for the classes to be analyzed. The classes on this class path are used during analysis but
     * aren't analyzed themselves.
     *
     * @return compile class path for the classes to be analyze
     */
    @Classpath
    public FileCollection getClasspath() {
        return classpath;
    }

    /**
     * @param classpath
     *            compile class path for the classes to be analyze
     */
    public void setClasspath(FileCollection classpath) {
        this.classpath = classpath;
    }

    /**
     * Class path holding the SpotBugs library.
     *
     * @return class path holding the SpotBugs library
     */
    @Classpath
    public FileCollection getSpotbugsClasspath() {
        return spotbugsClasspath;
    }

    /**
     * @param spotbugsClasspath
     *            class path holding the SpotBugs library
     */
    public void setSpotbugsClasspath(FileCollection spotbugsClasspath) {
        this.spotbugsClasspath = spotbugsClasspath;
    }

    /**
     * Class path holding any additional SpotBugs plugins.
     *
     * @return class path holding any additional SpotBugs plugin
     */
    @Classpath
    public FileCollection getPluginClasspath() {
        return pluginClasspath;
    }

    /**
     * @param pluginClasspath
     *            class path holding any additional SpotBugs plugin
     */
    public void setPluginClasspath(FileCollection pluginClasspath) {
        this.pluginClasspath = pluginClasspath;
    }

    /**
     * Whether or not to allow the build to continue if there are warnings.
     */
    @Input
    @Override
    public boolean getIgnoreFailures() {
        return ignoreFailures;
    }

    @Override
    public void setIgnoreFailures(boolean ignoreFailures) {
        this.ignoreFailures = ignoreFailures;
    }

    /**
     * Indicates whether analysis progress should be rendered on standard output. Defaults to false.
     * @return true iff progress report is enabled
     */
    @Input
    @Optional
    public boolean getShowProgress() {
        return showProgress;
    }

    public void setShowProgress(boolean showProgress) {
        this.showProgress = showProgress;
    }

    /**
     * The analysis effort level. The value specified should be one of {@code min}, {@code default}, or {@code max}.
     * Higher levels increase precision and find more bugs at the expense of running time and memory consumption.
     *
     * @return analysis effort level
     */
    @Input
    @Optional
    public String getEffort() {
        return effort;
    }

    /**
     * @param effort
     *            analysis effort level
     */
    public void setEffort(String effort) {
        this.effort = effort;
    }

    /**
     * The priority threshold for reporting bugs. If set to {@code low}, all bugs are reported. If set to {@code medium}
     * (the default), medium and high priority bugs are reported. If set to {@code
     * high}, only high priority bugs are reported.
     *
     * @return priority threshold for reporting bugs
     */
    @Input
    @Optional
    public String getReportLevel() {
        return reportLevel;
    }

    /**
     * @param reportLevel
     *            priority threshold for reporting bugs
     */
    public void setReportLevel(String reportLevel) {
        this.reportLevel = reportLevel;
    }

    /**
     * The maximum heap size for the forked spotbugs process (ex: '1g').
     *
     * @return maximum heap size
     */
    @Input
    @Optional
    public String getMaxHeapSize() {
        return maxHeapSize;
    }

    /**
     * @param maxHeapSize
     *            maximum heap size
     */
    public void setMaxHeapSize(String maxHeapSize) {
        this.maxHeapSize = maxHeapSize;
    }

    /**
     * The bug detectors which should be run. The bug detectors are specified by their class names, without any package
     * qualification. By default, all detectors which are not disabled by default are run.
     *
     * @return bug detectors which should be run
     */
    @Input
    @Optional
    public Collection<String> getVisitors() {
        return visitors;
    }

    /**
     * @param visitors
     *            bug detectors which should be run
     */
    public void setVisitors(Collection<String> visitors) {
        this.visitors = visitors;
    }

    /**
     * Similar to {@code visitors} except that it specifies bug detectors which should not be run. By default, no
     * visitors are omitted.
     *
     * @return bug detectors which should not be run
     */
    @Input
    @Optional
    public Collection<String> getOmitVisitors() {
        return omitVisitors;
    }

    /**
     * @param omitVisitors
     *            bug detectors which should not be run
     */
    public void setOmitVisitors(Collection<String> omitVisitors) {
        this.omitVisitors = omitVisitors;
    }

    /**
     * A filter specifying which bugs are reported. Replaces the {@code includeFilter} property.
     *
     * @return filter specifying which bugs are reported
     *
     * @since 2.2
     */
    @Incubating
    @Nested
    @Optional
    public TextResource getIncludeFilterConfig() {
        return includeFilterConfig;
    }

    /**
     * @param includeFilterConfig
     *            filter specifying which bugs are reported
     */
    public void setIncludeFilterConfig(TextResource includeFilterConfig) {
        this.includeFilterConfig = includeFilterConfig;
    }

    /**
     * A filter specifying bugs to exclude from being reported. Replaces the {@code excludeFilter} property.
     *
     * @return filter specifying bugs to exclude from being reported
     *
     * @since 2.2
     */
    @Incubating
    @Nested
    @Optional
    public TextResource getExcludeFilterConfig() {
        return excludeFilterConfig;
    }

    /**
     * @param excludeFilterConfig
     *            filter specifying bugs to exclude from being reported
     */
    public void setExcludeFilterConfig(TextResource excludeFilterConfig) {
        this.excludeFilterConfig = excludeFilterConfig;
    }

    /**
     * A filter specifying baseline bugs to exclude from being reported.
     *
     * @return filter specifying baseline bugs to exclude from being reported
     */
    @Incubating
    @Nested
    @Optional
    public TextResource getExcludeBugsFilterConfig() {
        return excludeBugsFilterConfig;
    }

    /**
     * @param excludeBugsFilterConfig
     *            filter specifying baseline bugs to exclude from being reported
     */
    public void setExcludeBugsFilterConfig(TextResource excludeBugsFilterConfig) {
        this.excludeBugsFilterConfig = excludeBugsFilterConfig;
    }

    /**
     * Any additional arguments (not covered here more explicitly like {@code effort}) to be passed along to SpotBugs.
     * <p>
     * Extra arguments are passed to SpotBugs after the arguments Gradle understands (like {@code effort} but before the
     * list of classes to analyze. This should only be used for arguments that cannot be provided by Gradle directly.
     * Gradle does not try to interpret or validate the arguments before passing them to SpotBugs.
     * <p>
     * See the <a href=
     * "https://code.google.com/p/spotbugs/source/browse/spotbugs/src/java/edu/umd/cs/spotbugs/TextUICommandLine.java">SpotBugs
     * TextUICommandLine source</a> for available options.
     *
     * @return any additional arguments (not covered here more explicitly like {@code effort}) to be passed along to
     *         SpotBugs
     *
     * @since 2.6
     */
    @Input
    @Optional
    public Collection<String> getExtraArgs() {
        return extraArgs;
    }

    /**
     * @param extraArgs
     *            any additional arguments (not covered here more explicitly like {@code effort}) to be passed along to
     *            SpotBugs
     */
    public void setExtraArgs(Collection<String> extraArgs) {
        this.extraArgs = extraArgs;
    }

    @Input
    @Optional
    public Collection<String> getJvmArgs() {
        return jvmArgs;
    }

    public void setJvmArgs(Collection<String> jvmArgs) {
        this.jvmArgs = jvmArgs;
    }

    /**
     * System properties passed to SpotBugs for additional configuration.
     * <p>
     * See the <a href="https://spotbugs.readthedocs.io/en/stable/analysisprops.html">Analysis Properties</a> section for available values.
     * @return The system properties to pass to the analysis
     */
    @Input
    @Optional
    public Map<String, Object> getSystemProperties() {
        return systemProperties;
    }

    public void setSystemProperties(Map<String, Object> systemProperties) {
        this.systemProperties = systemProperties;
    }
}
