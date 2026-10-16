package dev.wissam.sshterm.pq;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Objects;

/** Small experiment contract, not an Android terminal/forwarding API. */
public interface ProbeBackend {
    String COMMAND = "sshterm-pq-probe";
    String RESPONSE = "SSH_TERM_PROBE_OK\n";
    long TIMEOUT_MS = 5_000;

    /** Host is deliberately not configurable: this experiment cannot dial a remote host. */
    record Request(int port, Path knownHosts, KeyPair identity) {
        public Request {
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid loopback port");
            Objects.requireNonNull(identity, "Explicit disposable identity required");
            Objects.requireNonNull(knownHosts, "Explicit known_hosts required");
            if (!Files.isRegularFile(knownHosts) || !Files.isReadable(knownHosts)) {
                throw new IllegalArgumentException("Readable known_hosts file required");
            }
        }
        public String host() { return "127.0.0.1"; }
        public String username() { return "sshterm-probe"; }
    }

    record Evidence(String backend, String kex, boolean authenticated, String output) {
        public Evidence {
            if (!authenticated || !RESPONSE.equals(output)) {
                throw new IllegalStateException("No authenticated probe response");
            }
        }
    }

    Evidence run(Request request) throws Exception;

    /** Actual probe routing: missing flag uses SSHJ; typo/disabled MINA fails closed, no fallback. */
    static ProbeBackend select(String backend, boolean enableExperimentalMina, String hybridKex) {
        if (backend == null || backend.equals("sshj")) return new SshjProbeBackend();
        if (!backend.equals("mina-pq")) throw new IllegalArgumentException("Unknown backend: " + backend);
        if (!enableExperimentalMina) throw new IllegalStateException("MINA requires explicit experimental opt-in");
        return new MinaHybridBackend(hybridKex);
    }
}
