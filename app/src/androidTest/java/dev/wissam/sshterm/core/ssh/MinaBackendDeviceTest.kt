package dev.wissam.sshterm.core.ssh

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import java.io.File
import org.junit.Before
import org.junit.runner.RunWith

/** Real MINA/SSHJ localhost protocol tests on Android; no user keys or external endpoints.
 * Includes password, interactive, fresh deferred RSA/EC/Ed25519 signing, PTY/resize/exit,
 * local/remote forwarding, per-hop trust/signatures, cleanup, and no classical downgrade.
 * The app's real crypto-provider initialization runs first. Physical Keystore prompts are
 * intentionally outside this software-key fixture and require separate device acceptance.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class MinaBackendDeviceTest : MinaProtocolContract() {
    @Before
    fun configureMinaPrivateDirectories() {
        // SshServer initializes before production connect() in this fixture.
        val directory = ApplicationProvider.getApplicationContext<Context>().cacheDir.toPath()
        org.apache.sshd.common.util.io.PathUtils.setUserHomeFolderResolver { directory }
        org.apache.sshd.common.util.OsUtils.setCurrentWorkingDirectoryResolver { directory }
    }

    override fun newTrustFile(): File = File.createTempFile("mina-protocol-", ".known_hosts",
        ApplicationProvider.getApplicationContext<Context>().cacheDir)
}
