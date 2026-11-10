@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
package dev.ignorantshuffle.companion

import android.content.Intent
import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityNodeInfo

/** Walk the actual drawer and collection/clear UI through Android accessibility. */
class NavigationDeviceTest : InstrumentationTestCase() {
    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if(root==null)return emptyList()
        return listOf(root)+(0 until root.childCount).flatMap { nodes(root.getChild(it)) }
    }
    private fun find(label: String) = nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull {
        it.isVisibleToUser && (it.text?.toString()==label || it.contentDescription?.toString()==label)
    }
    private fun waitFor(label: String) {
        val until=System.currentTimeMillis()+8000
        while(System.currentTimeMillis()<until) { if(find(label)!=null)return;Thread.sleep(50) }
        fail("Missing accessible UI: $label")
    }
    private fun click(label: String, scroll: Boolean=false) {
        for(attempt in 0..12) {
            var node=find(label)
            if(node!=null) {
                while(node!=null && !node.isClickable) node=node.parent
                assertNotNull("No click action: $label",node)
                assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync();Thread.sleep(250);return
            }
            if(scroll) nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(100)
        }
        fail("Cannot click: $label")
    }
    fun testDrawerDestinationsAndStatisticsClearConfirmation() {
        for(command in listOf("input keyevent KEYCODE_WAKEUP", "wm dismiss-keyguard")) {
            instrumentation.uiAutomation.executeShellCommand(command).use { fd -> java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() } }
        }
        val activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            Thread.sleep(1500)
            if(find("Not now")!=null)click("Not now")
            for(page in listOf("Playlist","Queue","Statistics","Shuffle rules","Music folders","Playback","Notifications","Appearance","Diagnostics","Logs")) {
                click("Open navigation drawer");click(page,scroll=true);waitFor(page)
                when(page) {
                    "Playlist" -> waitFor("Search 0 tracks by title or artist")
                    "Queue" -> waitFor("App queue")
                    "Statistics" -> { waitFor("On-device listening");click("Data");click("Clear statistics");waitFor("Clear all statistics?");click("Cancel") }
                    "Shuffle rules" -> waitFor("Balance listening frequency")
                    "Playback" -> waitFor("Background playback")
                    "Diagnostics" -> waitFor("Playback debug mode")
                    "Logs" -> {waitFor("Search log");click("Tools");waitFor("Load saved history");click("Load saved history")}
                }
            }
        } finally { instrumentation.runOnMainSync {activity.finish()} }
    }
}
