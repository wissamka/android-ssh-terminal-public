package dev.wissam.sshterm.service

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.wissam.sshterm.core.ssh.recovery.EndedSession
import dev.wissam.sshterm.core.ssh.recovery.SessionRecoveryRepository
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** No SSH, credentials, sockets, service launch, adb commands, or external targets needed. */
@RunWith(AndroidJUnit4::class)
class BackgroundServiceContractTest {
    @Test fun installedServiceDeclaresSpecialUseAndIsPrivate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val component = ComponentName(context, SshForegroundService::class.java)
        @Suppress("DEPRECATION")
        val info = context.packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)
        assertFalse(info.exported)
        if (Build.VERSION.SDK_INT >= 34) {
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, info.foregroundServiceType)
            val subtype = context.packageManager.getProperty(
                "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE", component,
            )
            assertTrue(subtype.string?.contains("interactive SSH") == true)
        }
    }

    @Test fun atomicJournalSurvivesRepositoryRecreationAndDismissal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "recovery-test-${java.util.UUID.randomUUID()}")
        check(directory.mkdirs())
        val isolated = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File = directory
        }
        try {
            val first = SessionRecoveryRepository(isolated)
            first.opened(EndedSession("session-1", 123))
            assertTrue(first.ended.value.isEmpty())
            val recreated = SessionRecoveryRepository(isolated)
            assertEquals(listOf(EndedSession("session-1", 123)), recreated.ended.value)
            recreated.dismiss("session-1")
            assertTrue(SessionRecoveryRepository(isolated).ended.value.isEmpty())
        } finally { directory.deleteRecursively() }
    }
}
