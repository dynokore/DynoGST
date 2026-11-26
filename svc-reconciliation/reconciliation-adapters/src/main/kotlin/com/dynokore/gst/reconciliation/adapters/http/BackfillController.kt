package com.dynokore.gst.reconciliation.adapters.http

import com.dynokore.gst.reconciliation.contract.api.BackfillApi
import org.springframework.web.bind.annotation.RestController

/**
 * The backfill operations of the reconciliation contract (TPL-08). `projectBackfillEffect` answers 501 until its
 * endpoint task overrides it: the generated interface's default is NOT_IMPLEMENTED (INV-T-27707, INV-REQ-1090).
 */
@RestController
open class BackfillController : BackfillApi
