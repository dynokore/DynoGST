package com.dynokore.gst.transport.adapters.ap

import com.dynokore.gst.transport.application.AccessPointAdapters
import com.dynokore.gst.transport.application.PackageMember
import com.dynokore.gst.transport.application.PackageSender
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.TransportOutcome
import java.security.MessageDigest
import java.util.HexFormat

/**
 * The package sender over the registry of adapters (INV-REQ-424): [canSend] holds only for a registered adapter with
 * the [PackageSubmission] capability. One send is one request to the adapter. The document type code 380 is an
 * `INVOICE` and 381 a `CREDIT_NOTE`; any other code throws before any request. The packageId is the packageRef, a
 * hyphen and the first 32 hex characters of the SHA-256 of the members written `<docUuid>-v<version>` joined by `,`.
 */
class RegistryPackageSender(
    private val adapters: AccessPointAdapters,
) : PackageSender {
    override fun canSend(adapter: AdapterId): Boolean = adapters.find(adapter) is PackageSubmission

    override fun send(
        adapter: AdapterId,
        packageRef: String,
        documentTypeCode: String,
        members: List<PackageMember>,
    ): TransportOutcome {
        val type =
            requireNotNull(TYPE_OF_CODE[documentTypeCode]) { "document type code $documentTypeCode is not sent" }
        val target = adapters.find(adapter)
        check(target is PackageSubmission) { "adapter ${adapter.value} cannot send a package" }
        val documents =
            members.map {
                val text = it.payload.toString(Charsets.UTF_8)
                PackageDocument(it.doc, type, it.payload.size, text)
            }
        return target.submitPackage(packageId(packageRef, members), documents)
    }

    private fun packageId(
        packageRef: String,
        members: List<PackageMember>,
    ): String {
        val joined = members.joinToString(",") { "${it.doc.docUuid}-v${it.doc.version}" }
        val digest = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(Charsets.UTF_8))
        return packageRef + "-" + HexFormat.of().formatHex(digest).substring(0, HASH_CHARS)
    }

    private companion object {
        const val HASH_CHARS = 32
        val TYPE_OF_CODE = mapOf("380" to "INVOICE", "381" to "CREDIT_NOTE")
    }
}
