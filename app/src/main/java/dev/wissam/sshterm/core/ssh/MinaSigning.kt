package dev.wissam.sshterm.core.ssh

import java.security.PrivateKey
import java.security.PublicKey
import net.schmizz.sshj.DefaultConfig
import org.apache.sshd.common.NamedFactory
import org.apache.sshd.common.session.SessionContext
import org.apache.sshd.common.signature.BuiltinSignatures
import org.apache.sshd.common.signature.Signature

/** MINA's signer uses the SAME one-shot authorization and wire encoding as SSHJ. */
internal fun minaSignatureFactories(conn: ResolvedConnection, onDenied: (Exception) -> Unit = {}): List<NamedFactory<Signature>> {
    val safe = listOf(BuiltinSignatures.rsaSHA512, BuiltinSignatures.rsaSHA256,
        BuiltinSignatures.nistp256, BuiltinSignatures.nistp384, BuiltinSignatures.nistp521,
        BuiltinSignatures.ed25519).filter { it.isSupported }
    val auth = conn.auth as? SshAuthMethod.PublicKey ?: return safe
    val sshj = DefaultConfig().keyAlgorithms.associateBy { it.name }
    val protection = (auth.keyProvider as? dev.wissam.sshterm.core.keys.KeystoreKeyProvider)
        ?.signingProtection() ?: SigningProtection.SOFTWARE_APP_GATE
    return safe.filter { sshj.containsKey(it.getName()) }.map { factory ->
        object : NamedFactory<Signature> {
            override fun getName() = factory.getName()
            override fun create(): Signature {
                val verifier = factory.create()
                var signer: AuthorizedSignature? = null
                return object : Signature {
                    override fun getAlgorithm() = factory.getName()
                    override fun initVerifier(session: SessionContext?, key: PublicKey) = verifier.initVerifier(session, key)
                    override fun initSigner(session: SessionContext?, key: PrivateKey) {
                        check(signer == null)
                        signer = AuthorizedSignature(checkNotNull(sshj[factory.getName()]).create().newSignature(),
                            SigningRequest(conn.host, conn.port, conn.username, factory.getName(), protection),
                            auth.signingAuthorizer).also { it.initSign(key) }
                    }
                    override fun update(session: SessionContext?, data: ByteArray, off: Int, len: Int) {
                        signer?.update(data, off, len) ?: verifier.update(session, data, off, len)
                    }
                    override fun verify(session: SessionContext?, signature: ByteArray) = verifier.verify(session, signature)
                    override fun sign(session: SessionContext?): ByteArray {
                        try {
                            val signing = checkNotNull(signer)
                            // MINA adds the outer algorithm/string wrapper. encode converts ECDSA DER
                            // to SSH mpints and leaves RSA/Ed25519 payloads in their SSH wire format.
                            return signing.encode(signing.sign())
                        } catch (e: Exception) {
                            // Denial is terminal: MINA must not try a second signing algorithm.
                            onDenied(e)
                            (session as? org.apache.sshd.client.session.ClientSession)?.close(true)
                            throw SshFatalException("SSH signing denied or unavailable", e)
                        }
                    }
                }
            }
        }
    }
}
