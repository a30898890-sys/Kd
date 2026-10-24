package app.kd.assistant

/** Tiny main-thread message bus between ListenService and MainActivity. */
object KdBus {
    var logListener: ((String) -> Unit)? = null
    var stateListener: (() -> Unit)? = null
    private val pending = ArrayDeque<String>()

    fun log(msg: String) {
        val l = logListener
        if (l != null) {
            l(msg)
        } else {
            pending.addLast(msg)
            while (pending.size > 100) pending.removeFirst()
        }
    }

    fun flush() {
        val l = logListener ?: return
        while (pending.isNotEmpty()) l(pending.removeFirst())
    }

    fun stateChanged() {
        stateListener?.invoke()
    }
}
