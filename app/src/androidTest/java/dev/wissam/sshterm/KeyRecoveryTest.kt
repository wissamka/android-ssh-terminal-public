package dev.wissam.sshterm

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wissam.sshterm.core.keys.AesGcmFileStore
import dev.wissam.sshterm.core.keys.Ed25519BcRoutingProvider
import dev.wissam.sshterm.core.keys.GeneratedKeyAlgorithm
import dev.wissam.sshterm.core.keys.KeyManager
import dev.wissam.sshterm.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.Security
import java.security.Signature
import java.util.UUID

/** All material is generated for the test. No existing app key files or aliases are deleted. */
@RunWith(AndroidJUnit4::class)
class KeyRecoveryTest {
    companion object {
        @JvmStatic @BeforeClass fun providers() {
            // Same setup as the application; Android's built-in BC lacks the required primitives.
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.addProvider(BouncyCastleProvider())
            Security.removeProvider(Ed25519BcRoutingProvider.NAME)
            Security.insertProviderAt(Ed25519BcRoutingProvider(), 1)
        }
    }

    @Test fun softwareKeyStillDecryptsAndSignsAfterV1UpgradeAndDatabaseReopen() = withKeyFixture { context, manager, name ->
        val generated = manager.generate("migration-test", GeneratedKeyAlgorithm.ED25519)
        val originalPublic = manager.keyProviderFor(generated).public.encoded
        HistoricalDatabaseFixture.create(context, name, 1)
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL(
                "UPDATE keys SET name=?, algorithm=?, protection=?, storageRef=?, publicKeyOpenSsh=?, createdAt=? WHERE id=1",
                arrayOf<Any>(generated.name, generated.algorithm, generated.protection.name,
                    generated.storageRef, generated.publicKeyOpenSsh, generated.createdAt),
            )
        }
        AppDatabase.open(context, name).useDatabase { it.openHelper.writableDatabase }
        AppDatabase.open(context, name).useDatabase { db ->
            runBlocking {
                val recovered = db.keyDao().get(1)!!
                assertEquals(generated.copy(id = 1), recovered)
                assertEquals(1L, db.profileDao().get(1)!!.keyId)
                val provider = manager.keyProviderFor(recovered)
                assertArrayEquals(originalPublic, provider.public.encoded)
                val challenge = "migration-recovery-test".toByteArray()
                val signer = Signature.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
                signer.initSign(provider.private); signer.update(challenge)
                val signature = signer.sign()
                val verifier = Signature.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
                verifier.initVerify(provider.public); verifier.update(challenge)
                assertTrue(verifier.verify(signature))
            }
        }
    }

    @Test fun encryptedExportCanBeReimportedAndWrongPassphraseDoesNotDamageSource() = withKeyFixture { _, manager, _ ->
        val generated = manager.generate("export-test", GeneratedKeyAlgorithm.ED25519)
        val publicBefore = manager.keyProviderFor(generated).public.encoded
        val passphrase = "throwaway-test-passphrase".toCharArray()
        try {
            val exported = manager.exportKey(generated, passphrase)
            val imported = manager.import("recovered", exported, passphrase)
            assertArrayEquals(publicBefore, manager.keyProviderFor(imported, passphrase).public.encoded)
            assertThrows(Exception::class.java) {
                manager.import("wrong-passphrase", exported, "wrong-test-passphrase".toCharArray())
            }
            assertArrayEquals(publicBefore, manager.keyProviderFor(generated).public.encoded)
        } finally {
            passphrase.fill('\u0000')
        }
    }

    @Test fun missingEncryptedFileFailsWithoutDeletingKeyMetadataOrProfile() = withKeyFixture { context, manager, name ->
        val generated = manager.generate("missing-file-test", GeneratedKeyAlgorithm.ED25519)
        AppDatabase.open(context, name).useDatabase { db ->
            runBlocking {
                val keyId = db.keyDao().insert(generated)
                val profileId = db.profileDao().insert(dev.wissam.sshterm.data.db.ProfileEntity(
                    name = "recovery-target", host = "example.test", username = "test",
                    authType = dev.wissam.sshterm.data.db.AuthType.PUBLIC_KEY, keyId = keyId,
                ))
                // Only a throwaway file in this test's isolated filesDir is removed.
                assertTrue(File(context.filesDir, "keys/${generated.storageRef}").delete())
                assertThrows(Exception::class.java) { manager.keyProviderFor(generated) }
                assertEquals(generated.copy(id = keyId), db.keyDao().get(keyId))
                assertEquals(keyId, db.profileDao().get(profileId)!!.keyId)
            }
        }
    }

    private fun withKeyFixture(block: (Context, KeyManager, String) -> Unit) {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val directory = File(base.cacheDir, "key-recovery-test-$suffix").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = directory
        }
        val name = "key-recovery-test-$suffix.db"
        try {
            block(context, KeyManager(context, AesGcmFileStore(), dev.wissam.sshterm.core.keys.KeystoreMetadataInspector()), name)
        } finally {
            base.deleteDatabase(name)
            directory.deleteRecursively()
        }
    }
}
