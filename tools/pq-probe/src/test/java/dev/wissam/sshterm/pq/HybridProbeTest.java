package dev.wissam.sshterm.pq;

import java.nio.file.Files;
import org.junit.Test;
import static org.junit.Assert.*;

public class HybridProbeTest {
    @Test public void everyAdvertisedHybridAuthenticatesAndRunsCommand() throws Exception {
        for (String kex : MinaHybridBackend.HYBRIDS) {
            try (LocalFixture fixture = new LocalFixture(kex)) {
                byte[] trustBefore = Files.readAllBytes(fixture.knownHosts);
                var result = ProbeBackend.select("mina-pq", true, kex).run(fixture.request());
                assertEquals(kex, result.kex());
                assertTrue(result.authenticated());
                assertEquals(ProbeBackend.RESPONSE, result.output());
                assertEquals(1, fixture.commands.get());
                assertArrayEquals(trustBefore, Files.readAllBytes(fixture.knownHosts));
                System.out.println("verified hybrid=" + kex + " authenticated=true command=true");
            }
        }
    }
    @Test public void authenticatedSessionAloneIsNotSuccessEvidence() throws Exception {
        for (String response : new String[] {ProbeBackend.RESPONSE, "wrong response\n"}) {
            int exit = response.equals(ProbeBackend.RESPONSE) ? 1 : 0;
            try (LocalFixture fixture = new LocalFixture("mlkem768x25519-sha256", response, exit)) {
                assertThrows(Exception.class, () -> new MinaHybridBackend("mlkem768x25519-sha256").run(fixture.request()));
                assertTrue(fixture.authAttempts.get() > 0);
                assertEquals(1, fixture.commands.get());
            }
        }
    }
    @Test public void defaultBackendActuallyUsesSshj() throws Exception {
        try (LocalFixture fixture = new LocalFixture("curve25519-sha256")) {
            var result = ProbeBackend.select(null, false, null).run(fixture.request());
            assertEquals("sshj", result.backend());
            assertEquals("curve25519-sha256", result.kex());
            assertTrue(result.authenticated());
            assertEquals(1, fixture.commands.get());
        }
    }
    @Test public void experimentalFlagAndUnknownValuesFailClosed() {
        assertThrows(IllegalStateException.class, () -> ProbeBackend.select("mina-pq", false, "mlkem768x25519-sha256"));
        assertThrows(IllegalArgumentException.class, () -> ProbeBackend.select("mnia", true, null));
        assertThrows(IllegalArgumentException.class, () -> ProbeBackend.select("mina-pq", true, "curve25519-sha256"));
        assertThrows(IllegalArgumentException.class, () -> new MinaHybridBackend(null));
    }
    @Test public void unknownChangedRevokedAndWrongPortKeysFailBeforeAuthentication() throws Exception {
        for (String trust : new String[] {"unknown", "changed", "revoked", "wrong-port", "malformed"}) {
            try (LocalFixture fixture = new LocalFixture("mlkem768x25519-sha256")) {
                String entry = switch (trust) {
                    case "unknown" -> "";
                    case "malformed" -> "not-a-valid-known-host-line\n";
                    case "changed" -> fixture.entry(LocalFixture.newKey());
                    case "revoked" -> fixture.entry(fixture.hostKey) + "@revoked " + fixture.entry(fixture.hostKey);
                    default -> fixture.entry(fixture.hostKey).replace("]:" + fixture.port(), "]:1");
                };
                Files.writeString(fixture.knownHosts, entry);
                assertThrows(Exception.class, () -> new MinaHybridBackend("mlkem768x25519-sha256").run(fixture.request()));
                assertEquals(trust, 0, fixture.authAttempts.get());
                assertEquals(trust, 0, fixture.commands.get());
                assertEquals(entry, Files.readString(fixture.knownHosts));
            }
        }
    }
    @Test public void hashedKnownHostEntryWorksWithoutMutation() throws Exception {
        try (LocalFixture fixture = new LocalFixture("mlkem768x25519-sha256")) {
            byte[] salt = new byte[20];
            new java.security.SecureRandom().nextBytes(salt);
            var mac = javax.crypto.Mac.getInstance("HmacSHA1");
            mac.init(new javax.crypto.spec.SecretKeySpec(salt, "HmacSHA1"));
            String host = "[127.0.0.1]:" + fixture.port();
            var base64 = java.util.Base64.getEncoder();
            String hashed = "|1|" + base64.encodeToString(salt) + "|" +
                base64.encodeToString(mac.doFinal(host.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            String entry = fixture.entry(fixture.hostKey).replace(host, hashed);
            Files.writeString(fixture.knownHosts, entry);
            assertTrue(new MinaHybridBackend("mlkem768x25519-sha256").run(fixture.request()).authenticated());
            assertEquals(entry, Files.readString(fixture.knownHosts));
        }
    }
    @Test public void missingCryptoProviderFailsClosedBeforeStartingFixture() throws Exception {
        String classpath = java.util.Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
            .filter(path -> !path.contains("bcprov-") && !path.contains("bcpkix-") && !path.contains("bcutil-"))
            .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        var process = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp", classpath,
            LocalProbe.class.getName(), "--backend=mina-pq", "--enable-experimental-mina",
            "--kex=sntrup761x25519-sha512").redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertNotEquals(output, 0, process.exitValue());
            assertTrue(output, output.contains("Hybrid KEX unavailable"));
            assertFalse(output, output.contains("authenticated=true"));
        } finally { process.destroyForcibly(); }
    }
    @Test public void wrongIdentityDoesNotProduceSuccessEvidence() throws Exception {
        try (LocalFixture fixture = new LocalFixture("mlkem768x25519-sha256")) {
            var request = new ProbeBackend.Request(fixture.port(), fixture.knownHosts, LocalFixture.newKey());
            assertThrows(Exception.class, () -> new MinaHybridBackend("mlkem768x25519-sha256").run(request));
            assertTrue(fixture.authAttempts.get() > 0);
            assertEquals(0, fixture.commands.get());
        }
    }
    @Test public void classicalOnlyServerCannotDowngradeHybridProbe() throws Exception {
        try (LocalFixture fixture = new LocalFixture("curve25519-sha256")) {
            assertThrows(Exception.class, () -> new MinaHybridBackend("mlkem768x25519-sha256").run(fixture.request()));
            assertEquals(0, fixture.authAttempts.get());
            assertEquals(0, fixture.commands.get());
        }
    }
    @Test public void missingTrustFileFailsBeforeConnect() throws Exception {
        try (LocalFixture fixture = new LocalFixture("mlkem768x25519-sha256")) {
            Files.delete(fixture.knownHosts);
            assertThrows(IllegalArgumentException.class, fixture::request);
            assertEquals(0, fixture.authAttempts.get());
        }
    }
    @Test public void remoteTargetArgumentsAreNotSupported() {
        assertThrows(IllegalArgumentException.class, () -> LocalProbe.main(new String[] {"--host=example.com"}));
    }
}
