package app.kd.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Performs taps, typing and scrolling ONLY when CommandExecutor asks, after a spoken/typed command.
 * It does not store or transmit screen content. Must be enabled by the user in system settings.
 */
class KdAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: KdAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        KdBus.log("♿ অ্যাক্সেসিবিলিটি সার্ভিস চালু হয়েছে।")
        KdBus.stateChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        KdBus.stateChanged()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    private fun findScrollable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isScrollable) return n
        for (i in 0 until n.childCount) {
            val r = findScrollable(n.getChild(i))
            if (r != null) return r
        }
        return null
    }

    private fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable) return n
        for (i in 0 until n.childCount) {
            val r = findEditable(n.getChild(i))
            if (r != null) return r
        }
        return null
    }

    fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val n = findScrollable(root) ?: return false
        return n.performAction(
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        )
    }

    fun clickText(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(label) ?: return false
        for (n in nodes) {
            var cur: AccessibilityNodeInfo? = n
            var depth = 0
            while (cur != null && depth < 6) {
                if (cur.isClickable && cur.isEnabled) {
                    if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    break
                }
                cur = cur.parent
                depth++
            }
        }
        return false
    }

    private fun setText(node: AccessibilityNodeInfo, text: String, append: Boolean): Boolean {
        val existing = if (append && !node.isShowingHintText) node.text?.toString() ?: "" else ""
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, existing + text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        node.refresh()
        return node.text?.toString()?.contains(text) == true
    }

    /** True only if the text was set and read back from the field. */
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return false
        return setText(target, text, append = true)
    }

    /** Result codes: NOT_CHROME, NOT_FOUND, FAILED, TYPED, SUBMITTED. */
    fun addressBar(text: String): String {
        val root = rootInActiveWindow ?: return "NOT_FOUND"
        if (root.packageName?.toString() != "com.android.chrome") return "NOT_CHROME"
        val bar = root.findAccessibilityNodeInfosByViewId("com.android.chrome:id/url_bar")
            ?.firstOrNull() ?: return "NOT_FOUND"
        bar.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        bar.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (!setText(bar, text, append = false)) return "FAILED"
        if (Build.VERSION.SDK_INT >= 30) {
            val ok = bar.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            return if (ok) "SUBMITTED" else "TYPED"
        }
        return "TYPED"
    }
}
