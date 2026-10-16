package dev.wissam.sshterm.pq;

import java.time.Duration;
import java.util.List;
import org.apache.sshd.client.ClientBuilder;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.kex.BuiltinDHFactories;
import org.apache.sshd.common.signature.BuiltinSignatures;
import org.apache.sshd.common.kex.KexProposalOption;
import org.apache.sshd.common.io.nio2.Nio2ServiceFactoryFactory;

/** Hybrid-only localhost backend. No opportunistic classical downgrade or trust-store writes. */
public final class MinaHybridBackend implements ProbeBackend {
    public static final List<String> HYBRIDS = List.of(
        "mlkem768x25519-sha256", "sntrup761x25519-sha512", "sntrup761x25519-sha512@openssh.com");
    private final BuiltinDHFactories factory;

    public MinaHybridBackend(String kex) {
        if (kex == null || !HYBRIDS.contains(kex)) throw new IllegalArgumentException("Explicit supported hybrid KEX required");
        factory = BuiltinDHFactories.fromFactoryName(kex);
        if (factory == null || !factory.isSupported()) {
            throw new IllegalStateException("Hybrid KEX unavailable on this runtime: " + kex);
        }
    }

    @Override public Evidence run(Request request) throws Exception {
        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.setIoServiceFactoryFactory(new Nio2ServiceFactoryFactory());
            // Never inherit ~/.ssh/config, disk identities, agents or interactive/password callbacks.
            client.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
            client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
            client.setAgentFactory(null);
            client.setUserInteraction(null);
            client.setUserAuthFactories(List.of(UserAuthPublicKeyFactory.INSTANCE));
            var verifier = new KnownHostsServerKeyVerifier(RejectAllServerKeyVerifier.INSTANCE, request.knownHosts());
            verifier.setModifiedServerKeyAcceptor((session, address, entry, expected, actual) -> false);
            client.setServerKeyVerifier(verifier);
            // RSA fixture keys use SHA-2 signatures; do not re-enable legacy ssh-rsa/SHA-1.
            client.setSignatureFactories(List.of(BuiltinSignatures.rsaSHA512, BuiltinSignatures.rsaSHA256));
            client.setKeyExchangeFactories(List.of(ClientBuilder.DH2KEX.apply(factory)));
            client.start();
            try (ClientSession session = client.connect(request.username(), request.host(), request.port())
                    .verify(TIMEOUT_MS).getSession()) {
                session.addPublicKeyIdentity(request.identity());
                session.auth().verify(TIMEOUT_MS);
                String negotiated = session.getNegotiatedKexParameter(KexProposalOption.ALGORITHMS);
                if (!factory.getName().equals(negotiated)) throw new IllegalStateException("Hybrid negotiation mismatch");
                String output = session.executeRemoteCommand(COMMAND, Duration.ofMillis(TIMEOUT_MS));
                return new Evidence("mina-pq", negotiated, session.isAuthenticated(), output);
            } finally {
                client.stop();
            }
        }
    }
}
