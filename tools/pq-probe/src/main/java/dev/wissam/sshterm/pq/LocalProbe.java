package dev.wissam.sshterm.pq;

/** No target/user/key arguments: this command can only operate its own ephemeral fixture. */
public final class LocalProbe {
    public static void main(String[] args) throws Exception {
        String backend = null;
        String kex = "mlkem768x25519-sha256";
        boolean enabled = false;
        for (String arg : args) {
            if (arg.startsWith("--backend=")) backend = arg.substring("--backend=".length());
            else if (arg.equals("--enable-experimental-mina")) enabled = true;
            else if (arg.startsWith("--kex=")) kex = arg.substring("--kex=".length());
            else throw new IllegalArgumentException("Unsupported argument (remote targets are not supported): " + arg);
        }
        ProbeBackend selected = ProbeBackend.select(backend, enabled, kex);
        String serverKex = selected instanceof MinaHybridBackend ? kex : "curve25519-sha256";
        try (LocalFixture fixture = new LocalFixture(serverKex)) {
            var evidence = selected.run(fixture.request());
            System.out.println("backend=" + evidence.backend() + " kex=" + evidence.kex()
                + " authenticated=" + evidence.authenticated() + " command=verified scope=localhost-jvm-only");
        }
    }
}
