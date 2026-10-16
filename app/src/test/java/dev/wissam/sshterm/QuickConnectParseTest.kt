package dev.wissam.sshterm

import dev.wissam.sshterm.ui.terminal.QuickConnectTarget
import dev.wissam.sshterm.ui.terminal.parseQuickConnectTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickConnectParseTest {
    private fun parse(input: String) = parseQuickConnectTarget(input)

    @Test fun defaultPort() =
        assertEquals(QuickConnectTarget("root", "1.2.3.4", 22), parse("root@1.2.3.4"))

    @Test fun explicitPort() =
        assertEquals(QuickConnectTarget("me", "host.io", 2222), parse("me@host.io:2222"))

    @Test fun trimsWhitespace() =
        assertEquals(QuickConnectTarget("a", "b", 22), parse("  a@b  "))

    @Test fun rejectsNoUser() = assertNull(parse("@host"))
    @Test fun rejectsNoHost() = assertNull(parse("user@"))
    @Test fun rejectsNoAt() = assertNull(parse("userhost"))
    @Test fun rejectsBadPort() = assertNull(parse("u@h:99999"))
    @Test fun rejectsNonNumericPort() = assertNull(parse("u@h:abc"))

    @Test fun ipv6() {
        assertEquals(QuickConnectTarget("u", "2001:db8::1", 22), parse("u@2001:db8::1"))
        assertEquals(QuickConnectTarget("u", "::1", 22), parse("u@[::1]"))
        assertEquals(QuickConnectTarget("u", "2001:db8::1", 2222), parse("u@[2001:db8::1]:2222"))
        assertEquals(QuickConnectTarget("u", "fe80::1%eth0", 22), parse("u@[fe80::1%eth0]"))
    }
    @Test fun ipv6NeverTreatsFinalNumericGroupAsPort() {
        assertEquals(QuickConnectTarget("u", "2001:db8::2222", 22), parse("u@2001:db8::2222"))
        assertEquals(QuickConnectTarget("u", "::ffff:192.0.2.1", 2200), parse("u@[::ffff:192.0.2.1]:2200"))
        assertEquals(QuickConnectTarget("u", "1:2:3:4:5:6:7:8", 22), parse("u@1:2:3:4:5:6:7:8"))
    }
    @Test fun rejectsMalformed() {
        listOf("u@[::1", "u@::1]", "u@[::1]junk", "u@[::1]:", "u@h:0",
            "u@h:-1", "u@h:+22", "u@h:65536", "u@h:999999999999", "u@@h", "u@h h",
            "u@[]", "u@[host]:22", "u@h/path", "u@abc:def:123", "u@[:::1]",
            "u@[1::2::3]", "u@[1::2:]", "u@[:1::2]", "u@[::ffff:999.1.2.3]", "u@[::1%]", "u@[::1%a%b]").forEach { assertNull(it, parse(it)) }
    }
    @Test fun portBoundaries() {
        assertEquals(1, parse("u@h:1")?.port)
        assertEquals(65535, parse("u@h:65535")?.port)
    }
}
