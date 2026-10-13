package com.lumobrowser.app.vpn

import android.content.Context
import android.net.VpnService
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What a tunnel needs in order to exist.
 *
 * Every field here is something the *user* supplies. Nothing in this file has a default that would
 * let the app dial anything on its own: an empty [server] is the normal state, and it is what makes
 * the status panel say "not configured" rather than inventing an endpoint.
 *
 * Credentials are deliberately absent rather than present-and-ignored. A protocol that needs them
 * needs a protocol implementation to consume them, and storing a password for a tunnel that cannot
 * be built would be collecting a secret for no reason — the exact kind of thing the brief forbids.
 * When a backend is added it should bring its own credential storage, ideally Keystore-backed.
 */
@Immutable
data class VpnProfile(
    /** Hostname or IP the user typed. Empty until they do. */
    val server: String = "",
    val port: Int = 0,
    /** Which backend can consume this. `null` when nothing can. */
    val protocol: VpnProtocol? = null,
    /** Shown in the notification and the status panel. Never generated. */
    val displayName: String = "",
    /** Servers to push, empty = whatever the backend does by default. */
    val dnsServers: List<String> = emptyList(),
    /** Prefixes to route through the tunnel, empty = backend default. */
    val routes: List<String> = emptyList(),
    val mtu: Int = DEFAULT_MTU,
) {
    /** True when this is complete enough for a backend to attempt. */
    val isUsable: Boolean get() = server.isNotBlank() && port in 1..65_535 && protocol != null

    companion object {
        const val DEFAULT_MTU = 1400
        const val MIN_MTU = 576
        const val MAX_MTU = 9000
    }
}

/**
 * The protocols LUMO knows the *name* of.
 *
 * Naming a protocol here does not mean this build can speak it — see [TunnelBackends], which is
 * empty and is the only place that answers "can we actually connect". The enum exists so a profile
 * can record what the user intends, and so the UI can state plainly which protocol it would need.
 */
enum class VpnProtocol(val storageName: String) {
    WireGuard("wireguard"),
    OpenVpn("openvpn"),
    ;

    companion object {
        val Default: VpnProtocol? = null

        fun fromStorage(value: String?): VpnProtocol? =
            entries.firstOrNull { it.storageName == value }
    }
}

/**
 * The result of asking for a tunnel.
 *
 * [NoBackend] and [NotConfigured] are separated because they need different words in front of the
 * user: one is "this build cannot do it", the other is "you have not told me where to go". Collapsing
 * them into a single failure is how an app ends up blaming the user for a missing feature.
 */
sealed interface TunnelStartResult {
    data object Started : TunnelStartResult

    /** This build ships no protocol implementation. The honest, current answer. */
    data object NoBackend : TunnelStartResult

    /** A backend exists but the profile is incomplete. */
    data object NotConfigured : TunnelStartResult

    /** The user (or the system) declined the VPN consent dialog. */
    data object Unauthorized : TunnelStartResult

    data class Failed(val reason: String) : TunnelStartResult
}

/**
 * A real tunnel implementation.
 *
 * An implementation is expected to call `Builder.establish()` and keep the returned descriptor
 * alive, or to fail and say why. It is the only place allowed to make a tunnel exist, which is what
 * lets the rest of the app treat "is there a tunnel" as a question with one owner.
 */
interface TunnelBackend {
    /** Stable identifier, used in storage and logs. */
    val id: String

    /** Which protocol this speaks. */
    val protocol: VpnProtocol

    /** Human-readable name for the status panel. */
    val displayName: String

    /**
     * Configures [builder] and brings the tunnel up.
     *
     * Returns a descriptor that the caller owns and must close, or throws to report failure. The
     * builder is passed in rather than created here so that the service keeps ownership of the
     * interface it is configuring — a backend cannot quietly establish a tunnel nobody asked for.
     */
    suspend fun establish(
        context: Context,
        builder: VpnService.Builder,
        profile: VpnProfile,
    ): TunnelHandle
}

/**
 * A live tunnel. [close] tears it down; it must be safe to call twice.
 */
interface TunnelHandle : AutoCloseable {
    val describe: String
}

/**
 * Serialises a [VpnProfile] into one settings value.
 *
 * One value rather than seven DataStore keys, because a profile is one thing. Seven independent
 * keys give seven chances to persist a half-written profile — a new port saved against the previous
 * server — and they make "is it configured" a question about the consistency of a row instead of
 * the validity of a profile. A single record is written or it is not.
 *
 * The format is `v1|server|port|protocol|name|dns|routes|mtu` with `\`, `|`, newline and space
 * escaped in every value, so a server or display name typed by the user can contain anything. A
 * record that does not parse, or that carries a version this build does not know, decodes to an empty
 * profile: the safe reading of "I do not understand this" is "nothing is configured", never a guess
 * at an endpoint.
 */
object VpnProfileCodec {
    private const val VERSION = "v1"
    private const val SEPARATOR = '|'
    private const val FIELDS = 8

    fun encode(profile: VpnProfile): String = listOf(
        VERSION,
        escape(profile.server),
        profile.port.toString(),
        profile.protocol?.storageName.orEmpty(),
        escape(profile.displayName),
        encodeList(profile.dnsServers),
        encodeList(profile.routes),
        profile.mtu.toString(),
    ).joinToString(SEPARATOR.toString())

    fun decode(stored: String?): VpnProfile {
        if (stored.isNullOrBlank()) return VpnProfile()
        val fields = splitFields(stored)
        if (fields.size != FIELDS || fields[0] != VERSION) return VpnProfile()
        // Every field has to parse. A record with a corrupt port is a corrupt record: the eight fields
        // were written by one [encode] call describing one endpoint, so salvaging the other seven
        // would be inventing a value the user never typed. Decoding is read-only, so refusing the
        // whole thing loses nothing on disk.
        val port = fields[2].toIntOrNull()?.takeIf { it in 1..65_535 } ?: return VpnProfile()
        val mtu = fields[7].toIntOrNull()?.coerceIn(VpnProfile.MIN_MTU, VpnProfile.MAX_MTU)
            ?: return VpnProfile()
        return VpnProfile(
            server = unescape(fields[1]),
            port = port,
            protocol = VpnProtocol.fromStorage(fields[3].takeIf { it.isNotBlank() }),
            displayName = unescape(fields[4]),
            dnsServers = decodeList(fields[5]),
            routes = decodeList(fields[6]),
            mtu = mtu,
        )
    }

    /**
     * List entries are escaped one by one and joined with a space, so the space is a separator no
     * value can contain.
     *
     * Escaping the joined string instead would be wrong in a way that hides: the escaping would
     * protect the separators from the *field* splitter while leaving an entry's own space
     * indistinguishable from a join, so `1.1.1.1 8.8.8.8` typed as one entry would come back as two.
     */
    private fun encodeList(values: List<String>): String =
        values.filter { it.isNotBlank() }.joinToString(" ") { escape(it) }

    private fun decodeList(field: String): List<String> =
        field.split(' ').filter { it.isNotBlank() }.map { unescape(it) }

    private fun escape(value: String): String = buildString(value.length) {
        for (character in value) {
            when (character) {
                '\\' -> append("\\\\")
                SEPARATOR -> append("\\p")
                '\n' -> append("\\n")
                ' ' -> append("\\s")
                else -> append(character)
            }
        }
    }

    private fun unescape(value: String): String = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character == '\\' && index + 1 < value.length) {
                when (value[index + 1]) {
                    '\\' -> append('\\')
                    'p' -> append(SEPARATOR)
                    'n' -> append('\n')
                    's' -> append(' ')
                    else -> append(value[index + 1])
                }
                index += 2
            } else {
                append(character)
                index += 1
            }
        }
    }

    /** Splits on unescaped separators, keeping the escapes intact for [unescape]. */
    private fun splitFields(text: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        while (index < text.length) {
            val character = text[index]
            when {
                character == '\\' && index + 1 < text.length -> {
                    current.append(character).append(text[index + 1])
                    index += 2
                }

                character == SEPARATOR -> {
                    fields.add(current.toString())
                    current.setLength(0)
                    index += 1
                }

                else -> {
                    current.append(character)
                    index += 1
                }
            }
        }
        fields.add(current.toString())
        return fields
    }
}

/**
 * The tunnels this build can actually build.
 *
 * Empty. Not "empty for now" — empty because shipping a protocol means shipping an implementation
 * that has been run against a real server, and neither the implementation nor the server exists
 * here. The brief is specific that a `VpnService` subclass on its own is not a VPN, so this registry
 * is the single place where that claim would be made, and it makes the opposite claim instead.
 *
 * The consequence is visible rather than hidden: [LumoVpnService] starts, finds no backend, reports
 * [TunnelStartResult.NoBackend] and stops. Every surface that mentions connecting reads that result.
 *
 * Registering a backend is one call in [register] plus a test — no UI change, no state-machine
 * change, because the states below are already the ones a working tunnel needs.
 */
object TunnelBackends {
    private val lock = Any()
    private val backends = mutableListOf<TunnelBackend>()

    fun all(): List<TunnelBackend> = synchronized(lock) { backends.toList() }

    fun isAvailable(): Boolean = all().isNotEmpty()

    /**
     * The backend that can serve [profile], or `null`.
     *
     * Matching on protocol rather than "the only one" so that a second protocol cannot accidentally
     * take over the first one's profiles.
     */
    fun forProfile(profile: VpnProfile): TunnelBackend? {
        val wanted = profile.protocol ?: return null
        return all().firstOrNull { it.protocol == wanted }
    }

    /** Adds a backend. Intended for a future feature module and for tests that prove the seam works. */
    fun register(backend: TunnelBackend) {
        synchronized(lock) {
            backends.removeAll { it.id == backend.id }
            backends.add(backend)
        }
    }

    fun unregister(id: String) {
        synchronized(lock) { backends.removeAll { it.id == id } }
    }

    fun clear() {
        synchronized(lock) { backends.clear() }
    }
}

/**
 * Live tunnel state, as the service sees it.
 *
 * Process-scoped, which is correct because [LumoVpnService] runs in the app's own process. If it
 * ever needed `android:process=":vpn"` this would have to become a binder or a broadcast, and the
 * comment is here so that change is not made silently.
 *
 * State lives here rather than in the service instance because the service can be destroyed and
 * recreated by the system while the UI is still on screen, and the UI must not lose the answer it
 * was showing.
 */
object VpnTunnelStatus {
    private val _state = MutableStateFlow<TunnelRuntimeState>(TunnelRuntimeState.Idle)

    val state: StateFlow<TunnelRuntimeState> = _state.asStateFlow()

    fun publish(next: TunnelRuntimeState) {
        _state.value = next
    }

    fun reset() {
        _state.value = TunnelRuntimeState.Idle
    }
}

/**
 * What the service is doing, independent of what the platform's network list shows.
 *
 * Kept separate from `VpnConnectionState` on purpose. That enum answers "what should the status
 * panel say"; this one answers "what did our own service do". Conflating them is how an app ends up
 * reporting its own intent as an observation.
 */
sealed interface TunnelRuntimeState {
    /** No tunnel has been requested. */
    data object Idle : TunnelRuntimeState

    data object Connecting : TunnelRuntimeState

    /** A tunnel established by this app is up. The only state that justifies "connected". */
    data class Up(val describe: String) : TunnelRuntimeState

    data class Failed(val result: TunnelStartResult) : TunnelRuntimeState
}
