package com.asterix.voiceplayer.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {
    @Test fun compareVersions() {
        assertTrue(UpdateManager.compareVersions("1.0.1", "1.0.0") > 0)
        assertTrue(UpdateManager.compareVersions("1.10.0", "1.9.2") > 0)
        assertTrue(UpdateManager.compareVersions("1.0", "1.0.1") < 0)
        assertEquals(0, UpdateManager.compareVersions("2.0.0", "2.0"))
    }
}
