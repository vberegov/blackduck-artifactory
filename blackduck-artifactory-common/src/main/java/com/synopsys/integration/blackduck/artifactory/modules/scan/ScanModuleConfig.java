/*
 * blackduck-artifactory-common
 *
 * Copyright (c) 2021 Synopsys, Inc.
 *
 * Use subject to the terms and conditions of the Synopsys End User Software License and Maintenance Agreement. All rights reserved worldwide.
 */
package com.synopsys.integration.blackduck.artifactory.modules.scan;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import com.synopsys.integration.blackduck.artifactory.ArtifactoryPAPIService;
import com.synopsys.integration.blackduck.artifactory.DateTimeManager;
import com.synopsys.integration.blackduck.artifactory.configuration.ConfigurationPropertyManager;
import com.synopsys.integration.blackduck.artifactory.configuration.model.PropertyGroupReport;
import com.synopsys.integration.blackduck.artifactory.modules.ModuleConfig;

public class ScanModuleConfig extends ModuleConfig {
    private final String cron;
    private final String binariesDirectoryPath;
    private final String artifactCutoffDate;
    private final Boolean dryRun;
    private final List<String> namePatterns;
    private final Integer memory;
    private final Boolean repoPathCodelocation;
    private final List<String> repos;
    private final Boolean codelocationIncludeHostname;
    private final Boolean metadataBlockEnabled;

    private final File cliDirectory;

    private final DateTimeManager dateTimeManager;

    public ScanModuleConfig(Boolean enabled, String cron, String binariesDirectoryPath, String artifactCutoffDate, Boolean dryRun, List<String> namePatterns, Integer memory,
        Boolean repoPathCodelocation, List<String> repos, Boolean codelocationIncludeHostname, Boolean metadataBlockEnabled, File cliDirectory, DateTimeManager dateTimeManager) {
        super(ScanModule.class.getSimpleName(), enabled);
        this.cron = cron;
        this.binariesDirectoryPath = binariesDirectoryPath;
        this.artifactCutoffDate = artifactCutoffDate;
        this.dryRun = dryRun;
        this.namePatterns = namePatterns;
        this.memory = memory;
        this.repoPathCodelocation = repoPathCodelocation;
        this.repos = repos;
        this.codelocationIncludeHostname = codelocationIncludeHostname;
        this.metadataBlockEnabled = metadataBlockEnabled;
        this.cliDirectory = cliDirectory;
        this.dateTimeManager = dateTimeManager;
    }

    public static ScanModuleConfig createFromProperties(ConfigurationPropertyManager configurationPropertyManager, ArtifactoryPAPIService artifactoryPAPIService, File cliDirectory, DateTimeManager dateTimeManager)
        throws IOException {
        String cron = configurationPropertyManager.getProperty(ScanModuleProperty.CRON);
        String binariesDirectoryPath = configurationPropertyManager.getProperty(ScanModuleProperty.BINARIES_DIRECTORY_PATH);
        String artifactCutoffDate = configurationPropertyManager.getProperty(ScanModuleProperty.CUTOFF_DATE);
        Boolean dryRun = configurationPropertyManager.getBooleanProperty(ScanModuleProperty.DRY_RUN);
        Boolean enabled = configurationPropertyManager.getBooleanProperty(ScanModuleProperty.ENABLED);
        List<String> namePatterns = configurationPropertyManager.getPropertyAsList(ScanModuleProperty.NAME_PATTERNS);
        Integer memory = configurationPropertyManager.getIntegerProperty(ScanModuleProperty.MEMORY);
        Boolean repoPathCodelocation = configurationPropertyManager.getBooleanProperty(ScanModuleProperty.REPO_PATH_CODELOCATION);
        List<String> repos = configurationPropertyManager.getRepositoryKeysFromProperties(ScanModuleProperty.REPOS, ScanModuleProperty.REPOS_CSV_PATH).stream()
                                 .filter(artifactoryPAPIService::isValidRepository)
                                 .collect(Collectors.toList());
        Boolean codelocationIncludeHostname = configurationPropertyManager.getBooleanProperty(ScanModuleProperty.CODELOCATION_INCLUDE_HOSTNAME);
        Boolean metadataBlockEnabled = configurationPropertyManager.getBooleanProperty(ScanModuleProperty.METADATA_BLOCK);

        return new ScanModuleConfig(enabled, cron, binariesDirectoryPath, artifactCutoffDate, dryRun, namePatterns, memory, repoPathCodelocation, repos, codelocationIncludeHostname, metadataBlockEnabled, cliDirectory, dateTimeManager);
    }

    @Override
    public void validate(PropertyGroupReport propertyGroupReport) {
        validateCronExpression(propertyGroupReport, ScanModuleProperty.CRON, cron);
        validateNotBlank(propertyGroupReport, ScanModuleProperty.BINARIES_DIRECTORY_PATH, binariesDirectoryPath, "Please specify a path");
        validateDate(propertyGroupReport, ScanModuleProperty.CUTOFF_DATE, artifactCutoffDate, dateTimeManager);
        validateBoolean(propertyGroupReport, ScanModuleProperty.DRY_RUN, dryRun);
        validateBoolean(propertyGroupReport, ScanModuleProperty.ENABLED, isEnabledUnverified());
        validateInteger(propertyGroupReport, ScanModuleProperty.MEMORY, memory);
        validateList(propertyGroupReport, ScanModuleProperty.NAME_PATTERNS, namePatterns, "Please provide name patterns to match against");
        validateBoolean(propertyGroupReport, ScanModuleProperty.REPO_PATH_CODELOCATION, repoPathCodelocation);
        validateList(propertyGroupReport, ScanModuleProperty.REPOS, repos,
            String.format("No valid repositories specified. Please set the %s or %s property with valid repositories", ScanModuleProperty.REPOS.getKey(), ScanModuleProperty.REPOS_CSV_PATH.getKey()));
        validateBoolean(propertyGroupReport, ScanModuleProperty.METADATA_BLOCK, metadataBlockEnabled);
    }

    public String getCron() {
        return cron;
    }

    // TODO: Why isn't this being used?
    public String getBinariesDirectoryPath() {
        return binariesDirectoryPath;
    }

    public String getArtifactCutoffDate() {
        return artifactCutoffDate;
    }

    public Boolean getDryRun() {
        return dryRun;
    }

    public List<String> getNamePatterns() {
        return namePatterns;
    }

    public Integer getMemory() {
        return memory;
    }

    public Boolean getRepoPathCodelocation() {
        return repoPathCodelocation;
    }

    public List<String> getRepos() {
        return repos;
    }

    public File getCliDirectory() {
        return cliDirectory;
    }

    public Boolean getCodelocationIncludeHostname() {
        return codelocationIncludeHostname;
    }

    public Boolean isMetadataBlockEnabled() {
        return metadataBlockEnabled;
    }
}
