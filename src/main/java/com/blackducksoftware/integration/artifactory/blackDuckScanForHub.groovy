import groovy.transform.Field

import org.apache.commons.io.IOUtils
import org.apache.commons.lang3.StringUtils
import org.artifactory.fs.FileLayoutInfo
import org.artifactory.repo.RepoPath
import org.artifactory.resource.ResourceStreamHandle
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormat

import com.blackducksoftware.integration.hub.api.codelocation.CodeLocationItem
import com.blackducksoftware.integration.hub.api.policy.PolicyStatusItem
import com.blackducksoftware.integration.hub.api.project.version.ProjectVersionItem
import com.blackducksoftware.integration.hub.api.scan.ScanSummaryItem
import com.blackducksoftware.integration.hub.builder.HubScanConfigBuilder
import com.blackducksoftware.integration.hub.builder.HubServerConfigBuilder
import com.blackducksoftware.integration.hub.dataservice.cli.CLIDataService
import com.blackducksoftware.integration.hub.dataservice.policystatus.PolicyStatusDescription
import com.blackducksoftware.integration.hub.global.HubServerConfig
import com.blackducksoftware.integration.hub.rest.CredentialsRestConnection
import com.blackducksoftware.integration.hub.scan.HubScanConfig
import com.blackducksoftware.integration.hub.service.HubRequestService
import com.blackducksoftware.integration.hub.service.HubServicesFactory
import com.blackducksoftware.integration.log.Slf4jIntLogger
import com.blackducksoftware.integration.phone.home.enums.ThirdPartyName

@Field final String HUB_URL=""
@Field final int HUB_TIMEOUT=120
@Field final String HUB_USERNAME=""
@Field final String HUB_PASSWORD=""

@Field final String HUB_PROXY_HOST=""
//this is a String because right now, an int 0 is considered a valid port so results in an error if port=0 is combined with host=""
@Field final String HUB_PROXY_PORT= ""
@Field final String HUB_PROXY_IGNORED_PROXY_HOSTS=""
@Field final String HUB_PROXY_USERNAME=""
@Field final String HUB_PROXY_PASSWORD=""

@Field final int HUB_SCAN_MEMORY=4096
@Field final boolean HUB_SCAN_DRY_RUN=false

@Field final List<String> ARTIFACTORY_REPOS_TO_SEARCH=[
    "ext-release-local",
    "libs-release"
]
@Field final List<String> ARTIFACT_NAME_PATTERNS_TO_SCAN=[
    "*.war",
    "*.zip",
    "*.tar.gz",
    "*.hpi"
]

@Field final boolean logVerboseCronLog = false

@Field final String DATE_TIME_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS"
@Field final String BLACK_DUCK_SCAN_TIME_PROPERTY_NAME = "blackDuckScanTime"
@Field final String BLACK_DUCK_SCAN_RESULT_PROPERTY_NAME = "blackDuckScanResult"
@Field final String BLACK_DUCK_SCAN_CODE_LOCATION_URL_PROPERTY_NAME = "blackDuckScanCodeLocationUrl"
@Field final String BLACK_DUCK_PROJECT_VERSION_URL_PROPERTY_NAME = "blackDuckProjectVersionUrl"
@Field final String BLACK_DUCK_PROJECT_VERSION_UI_URL_PROPERTY_NAME = "blackDuckProjectVersionUiUrl"
@Field final String BLACK_DUCK_POLICY_STATUS_PROPERTY_NAME = "blackDuckPolicyStatus"
@Field final String BLACK_DUCK_OVERALL_POLICY_STATUS_PROPERTY_NAME = "blackDuckOverallPolicyStatus"

@Field boolean initialized = false
@Field File etcDir
@Field File blackDuckDirectory
@Field File cliDirectory
@Field HubServerConfig hubServerConfig

executions {
    /**
     * This will search your artifactory ARTIFACTORY_REPOS_TO_SEARCH repositories for the filename patterns designated in ARTIFACT_NAME_PATTERNS_TO_SCAN.
     * For example:
     *
     * ARTIFACTORY_REPOS_TO_SEARCH=["my-releases"]
     * ARTIFACT_NAME_PATTERNS_TO_SCAN=["*.war"]
     *
     * then this REST call will search 'my-releases' for all .war (web archive) files, scan them, and publish the BOM to the provided Hub server.
     *
     * The scanning process will add several properties to your artifacts in artifactory. Namely:
     *
     * blackDuckScanResult - SUCCESS or FAILURE, depending on the result of the scan
     * blackDuckScanTime - the last time a SUCCESS scan was completed
     * blackDuckScanCodeLocationUrl - the url for the code location created in the Hub
     *
     * The same functionality is provided via the scanForHub cron job to enable scheduled scans to run consistently.
     */
    scanForHub(httpMethod: "GET") { params ->
        log.info("Starting scanForHub REST request...")

        initializeConfiguration()
        Set<RepoPath> repoPaths = searchForRepoPaths()
        scanArtifactPaths(repoPaths)

        log.info("...completed scanForHub REST request.")
    }

    testConfig(httpMethod: "GET") { params ->
        log.info("Starting testConfig REST request...")

        initializeConfiguration()
        Set<RepoPath> repoPaths = searchForRepoPaths()
        scanArtifactPaths(repoPaths)

        log.info("...completed testConfig REST request.")
    }
}

jobs {
    /**
     * This will search your artifactory ARTIFACTORY_REPOS_TO_SEARCH repositories for the filename patterns designated in ARTIFACT_NAME_PATTERNS_TO_SCAN.
     * For example:
     *
     * ARTIFACTORY_REPOS_TO_SEARCH=["my-releases"]
     * ARTIFACT_NAME_PATTERNS_TO_SCAN=["*.war"]
     *
     * then this cron job will search 'my-releases' for all .war (web archive) files, scan them, and publish the BOM to the provided Hub server.
     *
     * The scanning process will add several properties to your artifacts in artifactory. Namely:
     *
     * blackDuckScanResult - SUCCESS or FAILURE, depending on the result of the scan
     * blackDuckScanTime - the last time a SUCCESS scan was completed
     * blackDuckScanCodeLocationUrl - the url for the code location created in the Hub
     *
     * The same functionality is provided via the scanForHub execution to enable a one-time scan triggered via a REST call.
     */
    scanForHub(cron: "0 0/1 * 1/1 * ?") {
        log.info("Starting scanForHub cron job...")

        initializeConfiguration()

        logCronRun("scanForHub")

        Set<RepoPath> repoPaths = searchForRepoPaths()
        scanArtifactPaths(repoPaths)

        log.info("...completed scanForHub cron job.")
    }

    /**
     * For those artifacts that were scanned successfully that have a blackDuckScanCodeLocationUrl, this cron job
     * will remove that property and add the blackDuckProjectVersionUiUrl property, which will link directly to your BOM in the Hub. It will also add the blackDuckProjectVersionUrl property which is needed for further Hub REST calls.
     */
    addProjectVersionUrl(cron: "0 0/1 * 1/1 * ?") {
        log.info("Starting addProjectVersionUrl cron job...")

        initializeConfiguration()

        logCronRun("addProjectVersionUrl")

        Set<RepoPath> repoPaths = searchForRepoPaths()
        CredentialsRestConnection credentialsRestConnection = new CredentialsRestConnection(hubServerConfig)
        HubServicesFactory hubServicesFactory = new HubServicesFactory(credentialsRestConnection)
        HubRequestService hubRequestService = hubServicesFactory.createHubRequestService()

        populateProjectVersionUrls(hubRequestService, repoPaths)

        log.info("...completed addProjectVersionUrl cron job.")
    }

    addPolicyStatus(cron: "0 0/1 * 1/1 * ?") {
        log.info("Starting addPolicyStatus cron job...")

        initializeConfiguration()

        logCronRun("addPolicyStatus")

        Set<RepoPath> repoPaths = searchForRepoPaths()
        CredentialsRestConnection credentialsRestConnection = new CredentialsRestConnection(hubServerConfig)
        HubServicesFactory hubServicesFactory = new HubServicesFactory(credentialsRestConnection)
        HubRequestService hubRequestService = hubServicesFactory.createHubRequestService()

        populatePolicyStatuses(hubRequestService, repoPaths)

        log.info("...completed addPolicyStatus cron job.")
    }
}

//PLEASE MAKE NO EDITS BELOW THIS LINE - NO TOUCHY!!!
def searchForRepoPaths() {
    def repoPaths = []
    ARTIFACT_NAME_PATTERNS_TO_SCAN.each {
        repoPaths.addAll(searches.artifactsByName(it, ARTIFACTORY_REPOS_TO_SEARCH.toArray(new String[ARTIFACTORY_REPOS_TO_SEARCH.size])))
    }

    repoPaths.toSet()
}

/**
 * If artifact's last modified time is newer than the scan time, or we have no record of the scan time, we should scan now.
 */
def shouldRepoPathBeScannedNow(RepoPath repoPath) {
    String blackDuckScanTimeProperty = repositories.getProperty(repoPath, BLACK_DUCK_SCAN_TIME_PROPERTY_NAME)
    if (StringUtils.isBlank(blackDuckScanTimeProperty)) {
        return true
    }

    def itemInfo = repositories.getItemInfo(repoPath)
    long lastModifiedTime = itemInfo.lastModified
    try {
        long blackDuckScanTime = DateTime.parse(blackDuckScanTimeProperty, DateTimeFormat.forPattern(DATE_TIME_PATTERN).withZoneUTC()).toDate().time
        return lastModifiedTime >= blackDuckScanTime
    } catch (Exception e) {
        //if the date format changes, the old format won't parse, so just cleanup the property by returning true and re-scanning
        log.error("Exception parsing the scan date (most likely the format changed): ${e.message}")
    }

    return true
}

def scanArtifactPaths(Set<RepoPath> repoPaths) {
    def filenamesToLayout = [:]
    def filenamesToRepoPath = [:]

    repoPaths = repoPaths.findAll {shouldRepoPathBeScannedNow(it)}
    repoPaths.each {
        ResourceStreamHandle resourceStream = repositories.getContent(it)
        FileLayoutInfo fileLayoutInfo = repositories.getLayoutInfo(it)
        def inputStream
        def fileOutputStream
        try {
            inputStream = resourceStream.inputStream
            fileOutputStream = new FileOutputStream(new File(blackDuckDirectory, it.name))
            fileOutputStream << inputStream
            filenamesToLayout.put(it.name, fileLayoutInfo)
            filenamesToRepoPath.put(it.name, it)
        } catch (Exception e) {
            log.error("There was an error getting ${it.name}: ${e.message}")
        } finally {
            IOUtils.closeQuietly(inputStream)
            IOUtils.closeQuietly(fileOutputStream)
        }
    }

    File toolsDirectory = cliDirectory
    File workingDirectory = blackDuckDirectory
    HubScanConfigBuilder hubScanConfigBuilder = new HubScanConfigBuilder(false);
    hubScanConfigBuilder.setScanMemory(HUB_SCAN_MEMORY);
    hubScanConfigBuilder.setDryRun(HUB_SCAN_DRY_RUN);
    hubScanConfigBuilder.setToolsDir(toolsDirectory);
    hubScanConfigBuilder.setWorkingDirectory(workingDirectory);
    hubScanConfigBuilder.setPluginVersion("1.1.1");
    hubScanConfigBuilder.setThirdPartyName(ThirdPartyName.ARTIFACTORY);
    hubScanConfigBuilder.setThirdPartyVersion("????");

    filenamesToLayout.each { key, value ->
        try {
            String project = value.module
            String version = value.baseRevision
            def scanFile = new File(workingDirectory, key)
            def scanTargetPath = scanFile.canonicalPath
            hubScanConfigBuilder.setProjectName(project);
            hubScanConfigBuilder.setVersion(version);
            hubScanConfigBuilder.addScanTargetPath(scanTargetPath);

            HubScanConfig hubScanConfig = hubScanConfigBuilder.build();

            CredentialsRestConnection credentialsRestConnection = new CredentialsRestConnection(hubServerConfig)
            HubServicesFactory hubServicesFactory = new HubServicesFactory(credentialsRestConnection)
            CLIDataService cliDataService = hubServicesFactory.createCLIDataService(new Slf4jIntLogger(log))

            List<ScanSummaryItem> scanSummaryItems = cliDataService.installAndRunScan(hubServerConfig, hubScanConfig)
            log.info("${key} was successfully scanned by the BlackDuck CLI.")
            repositories.setProperty(filenamesToRepoPath[key], BLACK_DUCK_SCAN_RESULT_PROPERTY_NAME, "SUCCESS")
            //we only scanned one path, so only one result is expected
            if (null != scanSummaryItems && scanSummaryItems.size() == 1) {
                try {
                    String codeLocationUrl = scanSummaryItems.get(0).getLink(ScanSummaryItem.CODE_LOCATION_LINK)
                    repositories.setProperty(filenamesToRepoPath[key], BLACK_DUCK_SCAN_CODE_LOCATION_URL_PROPERTY_NAME, codeLocationUrl)
                } catch (Exception e) {
                    log.error("Exception getting code location url: ${e.message}")
                }
            } else {
                log.warn("No scan summaries were available for a successful scan - if this was a dry run, this is expected, but otherwise, there should be summaries.")
            }
        } catch (Exception e) {
            log.error("Please investigate the scan logs for details - the Black Duck Scan did not complete successfully: ${e.message}", e)
            repositories.setProperty(filenamesToRepoPath[key], BLACK_DUCK_SCAN_RESULT_PROPERTY_NAME, "FAILURE")
        }

        String timeString = DateTime.now().withZone(DateTimeZone.UTC).toString(DateTimeFormat.forPattern(DATE_TIME_PATTERN).withZoneUTC())
        repositories.setProperty(filenamesToRepoPath[key], BLACK_DUCK_SCAN_TIME_PROPERTY_NAME, timeString)
        repositories.deleteProperty(filenamesToRepoPath[key], BLACK_DUCK_PROJECT_VERSION_URL_PROPERTY_NAME)
        repositories.deleteProperty(filenamesToRepoPath[key], BLACK_DUCK_PROJECT_VERSION_UI_URL_PROPERTY_NAME)
        repositories.deleteProperty(filenamesToRepoPath[key], BLACK_DUCK_POLICY_STATUS_PROPERTY_NAME)

        try {
            boolean deleteOk = new File(blackDuckDirectory, key).delete()
            log.info("Successfully deleted temporary ${key}: ${Boolean.toString(deleteOk)}")
        } catch (Exception e) {
            log.error("Exception deleting ${key}: ${e.message}")
        }
    }
}

def populateProjectVersionUrls(HubRequestService hubRequestService, Set<RepoPath> repoPaths) {
    repoPaths.each {
        String codeLocationUrl = repositories.getProperty(it, BLACK_DUCK_SCAN_CODE_LOCATION_URL_PROPERTY_NAME)
        if (StringUtils.isNotBlank(codeLocationUrl)) {
            codeLocationUrl = updateUrlPropertyToCurrentHubServer(codeLocationUrl)
            repositories.setProperty(it, BLACK_DUCK_SCAN_CODE_LOCATION_URL_PROPERTY_NAME, codeLocationUrl)
            CodeLocationItem codeLocationItem = hubRequestService.getItem(codeLocationUrl, CodeLocationItem.class)
            String mappedProjectVersionUrl = codeLocationItem.mappedProjectVersion
            if (StringUtils.isNotBlank(mappedProjectVersionUrl)) {
                String hubUrl = hubServerConfig.getHubUrl().toString()
                String versionId = mappedProjectVersionUrl.substring(mappedProjectVersionUrl.indexOf("/versions/") + "/versions/".length())
                String uiUrl = hubUrl + "/#versions/id:"+ versionId + "/view:bom"
                repositories.setProperty(it, BLACK_DUCK_PROJECT_VERSION_URL_PROPERTY_NAME, mappedProjectVersionUrl)
                repositories.setProperty(it, BLACK_DUCK_PROJECT_VERSION_UI_URL_PROPERTY_NAME, uiUrl)
                repositories.deleteProperty(it, BLACK_DUCK_SCAN_CODE_LOCATION_URL_PROPERTY_NAME)
                log.info("Added ${mappedProjectVersionUrl} to ${it.name}")
            }
        }
    }
}

def populatePolicyStatuses(HubRequestService hubRequestService, Set<RepoPath> repoPaths) {
    repoPaths.each {
        String projectVersionUrl = repositories.getProperty(it, BLACK_DUCK_PROJECT_VERSION_URL_PROPERTY_NAME)
        if (StringUtils.isNotBlank(projectVersionUrl)) {
            projectVersionUrl = updateUrlPropertyToCurrentHubServer(projectVersionUrl)
            repositories.setProperty(it, BLACK_DUCK_PROJECT_VERSION_URL_PROPERTY_NAME, projectVersionUrl)
            ProjectVersionItem projectVersionItem = hubRequestService.getItem(projectVersionUrl, ProjectVersionItem.class)
            String policyStatusUrl = projectVersionItem.getLink("policy-status")
            PolicyStatusItem policyStatusItem = hubRequestService.getItem(policyStatusUrl, PolicyStatusItem.class)
            PolicyStatusDescription policyStatusDescription = new PolicyStatusDescription(policyStatusItem)
            repositories.setProperty(it, BLACK_DUCK_POLICY_STATUS_PROPERTY_NAME, policyStatusDescription.policyStatusMessage)
            repositories.setProperty(it, BLACK_DUCK_OVERALL_POLICY_STATUS_PROPERTY_NAME, policyStatusItem.overallStatus.toString())
            log.info("Added policy status to ${it.name}")
        }
    }
}

/**
 * If the hub server being used changes, the existing properties in artifactory could be inaccurate so we will update them when they differ from the hub url established in the properties file.
 */
def String updateUrlPropertyToCurrentHubServer(String urlProperty) {
    if (urlProperty.startsWith(HUB_URL)) {
        return urlProperty
    }

    //get the old hub url from the existing property
    URL urlFromProperty = new URL(urlProperty)
    String hubUrlFromProperty = urlFromProperty.protocol + "://" + urlFromProperty.host
    if (urlFromProperty.port > 0) {
        hubUrlFromProperty += ":" + Integer.toString(urlFromProperty.port)
    }
    String urlEndpoint = urlProperty.replace(hubUrlFromProperty, "")

    String updatedProperty = HUB_URL + urlEndpoint
    updatedProperty
}

def logCronRun(String methodName) {
    if (logVerboseCronLog) {
        String timeString = DateTime.now().withZone(DateTimeZone.UTC).toString(DateTimeFormat.forPattern(DATE_TIME_PATTERN).withZoneUTC())
        def cronLogFile = new File(blackDuckDirectory, "blackduck_cron_history")
        cronLogFile << "${methodName}\t${timeString}${System.lineSeparator}"
    }
}

def initializeConfiguration() {
    if (!initialized) {
        etcDir = ctx.artifactoryHome.etcDir
        blackDuckDirectory = new File(etcDir, "plugins/blackducksoftware")
        cliDirectory = new File(blackDuckDirectory, "cli")
        cliDirectory.mkdirs()

        File cronLogFile = new File(blackDuckDirectory, "blackduck_cron_history")
        cronLogFile.createNewFile()

        HubServerConfigBuilder hubServerConfigBuilder = new HubServerConfigBuilder()
        hubServerConfigBuilder.setHubUrl(HUB_URL)
        hubServerConfigBuilder.setUsername(HUB_USERNAME)
        hubServerConfigBuilder.setPassword(HUB_PASSWORD)
        hubServerConfigBuilder.setTimeout(HUB_TIMEOUT)
        hubServerConfigBuilder.setProxyHost(HUB_PROXY_HOST)
        hubServerConfigBuilder.setProxyPort(HUB_PROXY_PORT)
        hubServerConfigBuilder.setIgnoredProxyHosts(HUB_PROXY_IGNORED_PROXY_HOSTS)
        hubServerConfigBuilder.setProxyUsername(HUB_PROXY_USERNAME)
        hubServerConfigBuilder.setProxyPassword(HUB_PROXY_PASSWORD)
        hubServerConfig = hubServerConfigBuilder.build()

        initialized = true
    }
}
