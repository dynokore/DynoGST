package com.dynokore.gst.notification.application.delivery

/**
 * Opens the sealed one-time passcode of a signed link in memory, just before send (INV-REQ-2762). The clear value is
 * held only by the caller for the one call; it is never stored, logged or put in an exception text.
 */
fun interface PasscodeOpener {
    /**
     * Returns the clear passcode of [sealed]; [label] is the address of the recipient the message was addressed to.
     *
     * @throws PasscodeUnopenable when the passcode cannot be opened.
     */
    fun open(
        sealed: String,
        label: String,
    ): String
}

/** The passcode could not be opened. The text is fixed: it names no value and no cause. */
class PasscodeUnopenable : RuntimeException(MESSAGE) {
    companion object {
        const val MESSAGE = "the passcode cannot be opened"
    }
}

/** Fails closed: opens nothing, so no passcode message is sent. */
object RefusingPasscodeOpener : PasscodeOpener {
    override fun open(
        sealed: String,
        label: String,
    ): String = throw PasscodeUnopenable()
}
