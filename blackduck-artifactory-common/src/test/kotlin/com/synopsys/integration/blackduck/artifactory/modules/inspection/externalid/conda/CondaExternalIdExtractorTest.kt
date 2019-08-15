package com.synopsys.integration.blackduck.artifactory.modules.inspection.externalid.conda

import com.synopsys.integration.bdio.model.externalid.ExternalIdFactory
import com.synopsys.integration.blackduck.artifactory.modules.inspection.model.SupportedPackageType
import org.artifactory.repo.RepoPath
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when` as mockWhen

internal class CondaExternalIdExtractorTest {
    private val externalIdFactory = ExternalIdFactory()
    private val condaExternalIdExtractor = CondaExternalIdExtractor(externalIdFactory)
    private val supportedPackageType = SupportedPackageType.CONDA

    @Test
    fun extractValidExternalId() {
        val parentRepoPath = mock(RepoPath::class.java)
        val repoPath: RepoPath = mock(RepoPath::class.java)
        mockWhen(repoPath.name).thenReturn("numpy-1.13.1-py27_0.tar.bz2")
        mockWhen(repoPath.parent).thenReturn(parentRepoPath)
        mockWhen(parentRepoPath.name).thenReturn("linux-64")

        val actualExternalId = condaExternalIdExtractor.extractExternalId(supportedPackageType, repoPath)
        val expectedExternalId = externalIdFactory.createNameVersionExternalId(supportedPackageType.forge, "numpy", "1.13.1-py27_0-linux-64")
        Assertions.assertEquals(expectedExternalId.createBdioId(), actualExternalId.get().createBdioId())
    }

    @Test
    fun extractInvalidFormatExternalId() {

    }

    @Test
    fun extractNoParentRepoPath() {

    }
}