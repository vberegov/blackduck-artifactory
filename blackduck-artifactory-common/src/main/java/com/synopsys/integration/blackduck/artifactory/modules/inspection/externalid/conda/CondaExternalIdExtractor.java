/*
 * blackduck-artifactory-common
 *
 * Copyright (c) 2021 Synopsys, Inc.
 *
 * Use subject to the terms and conditions of the Synopsys End User Software License and Maintenance Agreement. All rights reserved worldwide.
 */
package com.synopsys.integration.blackduck.artifactory.modules.inspection.externalid.conda;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
import org.artifactory.repo.RepoPath;
import org.slf4j.LoggerFactory;

import com.synopsys.integration.bdio.model.externalid.ExternalId;
import com.synopsys.integration.bdio.model.externalid.ExternalIdFactory;
import com.synopsys.integration.blackduck.artifactory.modules.inspection.model.SupportedPackageType;
import com.synopsys.integration.exception.IntegrationException;
import com.synopsys.integration.log.IntLogger;
import com.synopsys.integration.log.Slf4jIntLogger;
import com.synopsys.integration.util.NameVersion;

public class CondaExternalIdExtractor {
    private static final List<String> SUPPORTED_FILE_EXTENSIONS;

    static {
        SUPPORTED_FILE_EXTENSIONS = new ArrayList<>();
        SUPPORTED_FILE_EXTENSIONS.add(".tar.bz2");
        SUPPORTED_FILE_EXTENSIONS.add(".conda");
    }

    private final IntLogger logger = new Slf4jIntLogger(LoggerFactory.getLogger(this.getClass()));
    private final Pattern pattern = Pattern.compile("(.*)-(.*)-([^-|\\s]*)");

    private final ExternalIdFactory externalIdFactory;

    public CondaExternalIdExtractor(ExternalIdFactory externalIdFactory) {
        this.externalIdFactory = externalIdFactory;
    }

    public Optional<ExternalId> extractExternalId(SupportedPackageType supportedPackageType, RepoPath repoPath) {
        ExternalId externalId = null;
        try {
            NameVersion nameVersion = extractFileNamePieces(repoPath.getName());
            RepoPath parentRepoPath = repoPath.getParent();
            String name = nameVersion.getName();

            if (parentRepoPath == null) {
                throw new IntegrationException("Artifact does not have a parent folder. Cannot extract architecture.");
            }
            String architecture = parentRepoPath.getName().trim();
            String version = nameVersion.getVersion() + "-" + architecture;

            externalId = externalIdFactory.createNameVersionExternalId(supportedPackageType.getForge(), name, version);
        } catch (IntegrationException e) {
            logger.info(String.format("Failed to extract name version from filename on at %s", repoPath.getPath()));
            logger.debug(e.getMessage(), e);
        }

        return Optional.ofNullable(externalId);
    }

    private NameVersion extractFileNamePieces(String fileName) throws IntegrationException {
        Matcher matcher = pattern.matcher(fileName);
        if (!matcher.matches() || matcher.group(1).isEmpty() || matcher.group(2).isEmpty() || matcher.group(3).isEmpty()) {
            throw new IntegrationException("Failed to parse conda filename to extract component details.");
        }

        String buildStringExtension = matcher.group(3);
        String buildString = null;
        for (String supportedFileExtension : SUPPORTED_FILE_EXTENSIONS) {
            if (buildStringExtension.endsWith(supportedFileExtension)) {
                buildString = StringUtils.removeEnd(buildStringExtension, supportedFileExtension);
                break;
            }
        }
        if (buildString == null) {
            String supportedExtensionsMessage = "Supported conda file extensions are " + String.join(", ", SUPPORTED_FILE_EXTENSIONS);
            throw new IntegrationException(String.format("Failed to parse conda filename to extract component details. Likely unsupported file extension. %s", supportedExtensionsMessage));
        }

        String componentName = matcher.group(1).trim();
        String componentVersion = matcher.group(2).trim() + "-" + buildString;

        return new NameVersion(componentName, componentVersion);
    }
}
