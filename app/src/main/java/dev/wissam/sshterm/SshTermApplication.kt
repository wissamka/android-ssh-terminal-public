package dev.wissam.sshterm

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.wissam.sshterm.core.keys.Ed25519BcRoutingProvider
import java.security.Security
import net.schmizz.sshj.common.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider

@HiltAndroidApp
class SshTermApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Local-only crash capture (no network, no SDK) — see CrashLogger.
        dev.wissam.sshterm.core.CrashLogger.install(this)
        // Android ships a crippled, deprecated copy of BouncyCastle. Replace it with the
        // bundled full version so sshj has the ciphers/kex/key formats it expects, but
        // append (not prepend) so AndroidKeyStore/Conscrypt keep provider priority.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProvider())
        // Route just KeyFactory.Ed25519 to BC (see Ed25519BcRoutingProvider). Without this,
        // sshj's Ed25519KeyFactory falls through to Conscrypt on real Android and produces
        // OpenSslEdDsaPublicKey, which sshj's KeyType.fromKey doesn't recognise — so encoding
        // either a client Ed25519 auth packet OR a server Ed25519 host key throws
        // "Don't know how to encode key". Every other algorithm still resolves to
        // Conscrypt / AndroidKeyStore because this provider only advertises that one service.
        // Must run after BC is registered (the delegating SPI resolves BC by name).
        Security.removeProvider(Ed25519BcRoutingProvider.NAME)
        Security.insertProviderAt(Ed25519BcRoutingProvider(), 1)
        // Critical for hardware-backed keys: left to its own devices sshj registers BC as
        // its pinned provider and calls Signature.getInstance(alg, "BC"), which cannot sign
        // with non-exportable AndroidKeyStore private keys. Disabling sshj's registration
        // makes it use plain Signature.getInstance(alg) — JCA delayed provider selection
        // then routes Keystore keys to the Keystore provider and everything else to BC.
        SecurityUtils.setRegisterBouncyCastle(false)
    }
}
