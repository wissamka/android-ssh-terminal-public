package dev.wissam.sshterm.core.ssh

import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.userauth.method.ChallengeResponseProvider
import net.schmizz.sshj.userauth.password.Resource

/** Server-supplied text is displayed as a challenge, never interpreted as application instructions. */
data class InteractiveChallenge(val target: String, val name: String, val instructions: String, val prompt: String, val echo: Boolean)

/** A new provider per authentication attempt; no answers retained for reconnect or later rounds. */
internal class InteractiveChallenges(
    private val target: String,
    private val ask: suspend (InteractiveChallenge) -> CharArray?,
) : ChallengeResponseProvider {
    private var name = ""
    private var instructions = ""
    private var rounds = 0
    private var prompts = 0
    override fun getSubmethods(): List<String> = emptyList()
    override fun shouldRetry() = false
    override fun init(resource: Resource<*>?, name: String?, instruction: String?) {
        if (++rounds > 16) throw SshFatalException("Too many authentication challenge rounds")
        this.name = name.orEmpty().take(4096)
        instructions = instruction.orEmpty().take(4096)
    }
    override fun getResponse(prompt: String?, echo: Boolean): CharArray {
        if (++prompts > 64) throw SshFatalException("Too many authentication challenges")
        return runBlocking { kotlinx.coroutines.withTimeout(90_000) { ask(InteractiveChallenge(target, name, instructions, prompt.orEmpty().take(4096), echo)) } }
            ?: throw SshFatalException("Authentication cancelled")
    }
}
