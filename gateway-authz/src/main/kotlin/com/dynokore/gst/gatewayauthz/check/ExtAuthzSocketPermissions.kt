package com.dynokore.gst.gatewayauthz.check

import org.springframework.context.ApplicationListener
import org.springframework.context.SmartLifecycle
import org.springframework.grpc.server.lifecycle.GrpcServerStartedEvent
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * The one operation through which every permission change of the extAuth socket and its directory goes. Production
 * uses [Files.setPosixFilePermissions]; a bean of this type replaces it.
 */
fun interface PermissionChange {
    @Throws(IOException::class)
    fun apply(
        path: Path,
        permissions: Set<PosixFilePermission>,
    )
}

/**
 * Opens the pod-local extAuth socket to its group and to nobody else (INV-AC-28810; least privilege, NIST SP 800-190):
 * Envoy runs as another user of the same group, and connecting to a Unix socket needs write permission on it. The
 * group itself is set by the deployment and is not changed here.
 *
 * - Before the gRPC server binds (this lifecycle's phase is just below the server's), the socket's parent directory is
 *   created if absent, without creating its own parents, and gets exactly `rwxrwx---` unless it already has it.
 * - After the bind, on [GrpcServerStartedEvent], the socket gets exactly `rw-rw----`. The event is delivered
 *   synchronously inside the server's start, so readiness cannot answer 200 before this is done.
 *
 * It fails closed: any failure fails the application's start with an error naming the absolute path concerned.
 *
 * [socketPath] is the path the server binds (`spring.grpc.server.netty.domain-socket-path`); null or blank means no
 * Unix socket is configured, and there is nothing to open.
 */
class ExtAuthzSocketPermissions(
    private val socketPath: () -> String?,
    private val permissionChange: PermissionChange,
) : SmartLifecycle,
    ApplicationListener<GrpcServerStartedEvent> {
    @Volatile
    private var running = false

    override fun start() {
        socket()?.let { prepareDirectory(checkNotNull(it.parent) { "The socket path $it has no parent directory" }) }
        running = true
    }

    override fun stop() {
        running = false
    }

    override fun isRunning(): Boolean = running

    /** The gRPC server's lifecycle is in the last phase ([Int.MAX_VALUE]); this one starts just before it. */
    override fun getPhase(): Int = SmartLifecycle.DEFAULT_PHASE - 1

    override fun onApplicationEvent(event: GrpcServerStartedEvent) {
        val socket = socket() ?: return
        failingClosed("Cannot set the permissions of the extAuth socket $socket") {
            permissionChange.apply(socket, SOCKET_PERMISSIONS)
        }
    }

    private fun socket(): Path? = socketPath()?.takeIf { it.isNotBlank() }?.let { Path.of(it).toAbsolutePath() }

    private fun prepareDirectory(directory: Path) {
        failingClosed("Cannot create the extAuth socket directory $directory") {
            if (Files.notExists(directory)) {
                // Not recursive. The mode given at creation never holds a permission for others, whatever the umask.
                Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS))
            }
        }
        check(Files.isDirectory(directory)) { "The extAuth socket directory $directory is not a directory" }
        failingClosed("Cannot set the permissions of the extAuth socket directory $directory") {
            if (Files.getPosixFilePermissions(directory) != DIRECTORY_PERMISSIONS) {
                permissionChange.apply(directory, DIRECTORY_PERMISSIONS)
            }
        }
    }

    private fun failingClosed(
        message: String,
        step: () -> Unit,
    ) {
        // Whatever the failure, it is rethrown with the path in its message: nothing is swallowed.
        runCatching(step).onFailure { throw IllegalStateException(message, it) }
    }

    private companion object {
        val DIRECTORY_PERMISSIONS: Set<PosixFilePermission> = PosixFilePermissions.fromString("rwxrwx---")
        val SOCKET_PERMISSIONS: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-rw----")
    }
}
