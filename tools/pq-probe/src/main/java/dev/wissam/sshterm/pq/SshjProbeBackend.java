package dev.wissam.sshterm.pq;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts;
import net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper;

/** Existing-library baseline for the same strict authenticated localhost experiment. */
public final class SshjProbeBackend implements ProbeBackend {
    @Override public Evidence run(Request request) throws Exception {
        try (SSHClient client = new SSHClient()) {
            client.setConnectTimeout((int) TIMEOUT_MS);
            client.setTimeout((int) TIMEOUT_MS);
            var negotiated = new AtomicReference<String>();
            client.addAlgorithmsVerifier(algorithms -> {
                negotiated.set(algorithms.getKeyExchangeAlgorithm());
                return "curve25519-sha256".equals(algorithms.getKeyExchangeAlgorithm());
            });
            client.addHostKeyVerifier(new OpenSSHKnownHosts(request.knownHosts().toFile()));
            client.connect(request.host(), request.port());
            client.authPublickey(request.username(), new KeyPairWrapper(request.identity()));
            try (Session session = client.startSession(); Session.Command command = session.exec(COMMAND)) {
                command.join(TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (!Integer.valueOf(0).equals(command.getExitStatus())) {
                    throw new IllegalStateException("Probe did not exit successfully");
                }
                String output = new String(command.getInputStream().readNBytes(1024), StandardCharsets.UTF_8);
                return new Evidence("sshj", negotiated.get(), client.isAuthenticated(), output);
            }
        }
    }
}
