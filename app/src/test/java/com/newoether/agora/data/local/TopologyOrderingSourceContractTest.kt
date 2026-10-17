package com.newoether.agora.data.local

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class TopologyOrderingSourceContractTest {
    @Test
    fun topologySnapshotAndFlowUseTimestampAndIdOrdering() {
        val source = File(
            locateMainSourceRoot(),
            "com/newoether/agora/data/local/ChatProviderContextDao.kt",
        ).readText()

        assertEquals(2, "ORDER BY timestamp ASC, id ASC".toRegex().findAll(source).count())
    }

    private fun locateMainSourceRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/src/main/java"),
                File(directory, "src/main/java"),
            ).firstOrNull(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate the main Java source directory")
    }
}
