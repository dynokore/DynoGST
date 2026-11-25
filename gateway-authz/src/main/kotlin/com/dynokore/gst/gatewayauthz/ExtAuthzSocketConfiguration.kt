package com.dynokore.gst.gatewayauthz

import com.dynokore.gst.gatewayauthz.check.ExtAuthzSocketPermissions
import com.dynokore.gst.gatewayauthz.check.PermissionChange
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Files

/**
 * The permissions of the Unix socket the gRPC server binds and of its directory (INV-AC-28810). The path is the one in
 * the server's own bound properties. A [PermissionChange] bean replaces the production operation,
 * [Files.setPosixFilePermissions].
 */
@Configuration(proxyBeanMethods = false)
class ExtAuthzSocketConfiguration {
    @Bean
    fun extAuthzSocketPermissions(
        grpcServer: ObjectProvider<GrpcServerProperties>,
        permissionChange: ObjectProvider<PermissionChange>,
    ): ExtAuthzSocketPermissions =
        ExtAuthzSocketPermissions(
            { grpcServer.ifAvailable?.netty?.domainSocketPath },
            permissionChange.ifAvailable ?: PermissionChange { path, permissions ->
                Files.setPosixFilePermissions(path, permissions)
            },
        )
}
