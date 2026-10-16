package dev.wissam.sshterm.pq;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.kex.BuiltinDHFactories;
import org.apache.sshd.common.signature.BuiltinSignatures;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.ServerBuilder;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;

/** Disposable in-process server: loopback only, ephemeral keys, fixed command, no OS shell. */
public final class LocalFixture implements AutoCloseable {
    private final SshServer server;
    public final KeyPair hostKey = newKey();
    public final KeyPair identity = newKey();
    public final AtomicInteger authAttempts = new AtomicInteger();
    public final AtomicInteger commands = new AtomicInteger();
    public final Path directory = Files.createTempDirectory("sshterm-pq-fixture-");
    public final Path knownHosts = directory.resolve("known_hosts");

    public LocalFixture(String onlyKex) throws Exception {
        this(onlyKex, ProbeBackend.RESPONSE, 0);
    }
    public LocalFixture(String onlyKex, String response, int exitStatus) throws Exception {
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(KeyPairProvider.wrap(hostKey));
        server.setSignatureFactories(List.of(BuiltinSignatures.rsaSHA512, BuiltinSignatures.rsaSHA256));
        server.setPasswordAuthenticator((user, password, session) -> false);
        server.setPublickeyAuthenticator((user, key, session) -> {
            authAttempts.incrementAndGet();
            return user.equals("sshterm-probe") && KeyUtils.compareKeys(identity.getPublic(), key);
        });
        if (onlyKex != null) {
            var factory = BuiltinDHFactories.fromFactoryName(onlyKex);
            if (factory == null || !factory.isSupported()) throw new IllegalArgumentException("Server KEX unavailable");
            server.setKeyExchangeFactories(List.of(ServerBuilder.DH2KEX.apply(factory)));
        }
        server.setCommandFactory((channel, command) -> new Command() {
            private OutputStream out;
            private ExitCallback exit;
            @Override public void setInputStream(InputStream in) { }
            @Override public void setOutputStream(OutputStream output) { out = output; }
            @Override public void setErrorStream(OutputStream err) { }
            @Override public void setExitCallback(ExitCallback callback) { exit = callback; }
            @Override public void start(ChannelSession channel, Environment environment) throws IOException {
                commands.incrementAndGet();
                if (!ProbeBackend.COMMAND.equals(command)) { exit.onExit(126); return; }
                out.write(response.getBytes(StandardCharsets.UTF_8));
                out.flush();
                exit.onExit(exitStatus);
            }
            @Override public void destroy(ChannelSession channel) { }
        });
        try {
            server.start();
            Files.writeString(knownHosts, entry(hostKey));
        } catch (Exception failure) {
            close();
            throw failure;
        }
    }
    public int port() { return server.getPort(); }
    public String entry(KeyPair key) {
        return "[127.0.0.1]:" + port() + " " + PublicKeyEntry.toString(key.getPublic()) + "\n";
    }
    public ProbeBackend.Request request() { return new ProbeBackend.Request(port(), knownHosts, identity); }
    public static KeyPair newKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
    @Override public void close() throws IOException {
        try { server.stop(true); }
        finally {
            Files.deleteIfExists(knownHosts);
            Files.deleteIfExists(directory);
        }
    }
}
