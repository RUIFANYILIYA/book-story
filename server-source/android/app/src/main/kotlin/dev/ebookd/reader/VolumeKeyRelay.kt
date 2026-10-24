package dev.ebookd.reader

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one wire between the activity's key handling and whichever screen is open.
 *
 * Volume keys are the only hardware buttons a reader can borrow, and the plan is explicit that the
 * override belongs on the activity rather than in Compose key handling — a panel that takes focus
 * stops receiving them exactly when the reader is adjusting something. There is one activity, so a
 * relay is enough: the reader screen installs a handler while it is composed and takes it down
 * again, and the activity asks the relay rather than knowing about the reader at all.
 *
 * A null handler means nobody wants the keys, which is also the answer for every other screen —
 * the volume rocker keeps doing what it says on it.
 */
@Singleton
class VolumeKeyRelay
    @Inject
    constructor() {
        /**
         * Returns whether the key was consumed. A handler must answer `false` for anything it is not
         * sure about: claiming a key it does not act on silences the volume panel for no reason.
         *
         * The action is passed through because a press arrives as two events — down and up — and both
         * have to be answered, but only one of them may act. A handler that turned a page on each
         * event turned two pages per press; measured on the emulator, one `input keyevent 25` moved
         * 3/8 → 5/8. Swallowing the up without acting is also what keeps the system from showing the
         * volume panel for the half of the press we did not claim.
         */
        var handler: ((keyCode: Int, action: Int) -> Boolean)? = null
    }
