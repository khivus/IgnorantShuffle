@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
package dev.ignorantshuffle.companion

import android.content.Intent
import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect

/** Walk the actual drawer and collection/clear UI through Android accessibility. */
class NavigationDeviceTest : InstrumentationTestCase() {
    private fun root(): AccessibilityNodeInfo? {
        if(android.os.Build.VERSION.SDK_INT>=33) instrumentation.uiAutomation.clearCache()
        return instrumentation.uiAutomation.rootInActiveWindow
    }
    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if(root==null)return emptyList()
        return listOf(root)+(0 until root.childCount).flatMap { nodes(root.getChild(it)) }
    }
    private fun find(label: String) = nodes(root()).firstOrNull {
        it.isVisibleToUser && (it.text?.toString()==label || it.contentDescription?.toString()==label ||
            (it.isCheckable && it.text?.toString()?.startsWith(label)==true))
    }
    private fun waitFor(label: String) {
        val until=System.currentTimeMillis()+8000
        while(System.currentTimeMillis()<until) { if(find(label)!=null)return;Thread.sleep(50) }
        fail("Missing accessible UI: $label")
    }
    private fun click(label: String, scroll: Boolean=false, toggle: Boolean=false) {
        var direction=AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        var previousLabels: String?=null
        for(attempt in 0..60) {
            var node=find(label)
            if(node!=null) {
                if(toggle && !node.isCheckable) {
                    var parent=node.parent
                    node=null
                    while(parent!=null) {
                        val control=nodes(parent).firstOrNull {it.isCheckable && it.isClickable}
                        if(control!=null) { node=control.takeIf {it.isVisibleToUser};break }
                        parent=parent.parent
                    }
                } else while(node!=null && !node.isClickable) node=node.parent
                if(node!=null && node.refresh() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    instrumentation.waitForIdleSync();Thread.sleep(250);return
                }
            }
            if(scroll) {
                val visible=nodes(root()).filter { it.isVisibleToUser }
                val labels=visible.mapNotNull { it.text?.toString() }.joinToString()
                if(previousLabels==labels) direction=if(direction==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                previousLabels=labels
                val container=visible.lastOrNull { it.isScrollable }
                if(container!=null) {
                    val bounds=Rect();container.getBoundsInScreen(bounds)
                    val top=bounds.top+bounds.height()/5;val bottom=bounds.bottom-bounds.height()/5
                    val from=if(direction==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)bottom else top
                    val to=if(direction==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)top else bottom
                    instrumentation.uiAutomation.executeShellCommand("input swipe ${bounds.centerX()} $from ${bounds.centerX()} $to 300").use { fd ->
                        java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
                    }
                }
                Thread.sleep(200)
            }
            Thread.sleep(100)
        }
        fail("Cannot click: $label. Visible UI: " + nodes(root()).filter { it.isVisibleToUser }.joinToString { "${it.text}/${it.contentDescription}" })
    }
    fun testDrawerDestinationsAndStatisticsClearConfirmation() {
        assertEquals("Ignorant Shuffle", instrumentation.targetContext.applicationInfo.loadLabel(instrumentation.targetContext.packageManager).toString())
        for(command in listOf("input keyevent KEYCODE_WAKEUP", "wm dismiss-keyguard")) {
            instrumentation.uiAutomation.executeShellCommand(command).use { fd -> java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() } }
        }
        val activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            Thread.sleep(1500)
            if(find("Not now")!=null)click("Not now")
            waitFor("Disconnected")
            for((index,page) in listOf("Playlist","Queue","Statistics","Shuffle rules","Music folders","Playback","Notifications","Appearance","Diagnostics","Logs").withIndex()) {
                click("Open navigation drawer")
                if(index==0) {
                    waitFor("IgnorantShuffle");waitFor("LISTEN & EXPLORE")
                    if(android.os.Build.VERSION.SDK_INT>=28) {
                        var header=find("LISTEN & EXPLORE")
                        while(header!=null && !header.isHeading)header=header.parent
                        assertNotNull("Drawer group must be an accessibility heading",header)
                        assertFalse("Drawer group must not behave like a page",header!!.isClickable)
                    }
                }
                click(page,scroll=true);waitFor(page)
                when(page) {
                    "Playlist" -> waitFor("Search 0 tracks by title or artist")
                    "Queue" -> waitFor("App queue")
                    "Statistics" -> { waitFor("Library listening");click("Data");click("Clear statistics");waitFor("Clear all statistics?");click("Cancel") }
                    "Shuffle rules" -> {
                        waitFor("Balance listening frequency")
                        val controller=(instrumentation.targetContext.applicationContext as ShuffleApplication).controller
                        val original=controller.preferences
                        var observed=original
                        try {
                            instrumentation.runOnMainSync {controller.changePreferences(original.copy(smartWeights=SmartWeightOptions()))}
                            click("Learn from listening",scroll=true,toggle=true)
                            instrumentation.runOnMainSync {observed=controller.preferences}
                            assertTrue("Smart switch must enable learning",observed.smartWeights.enabled)
                            click("Gentle",scroll=true);click("±5%",scroll=true)
                            click("Early skips",scroll=true,toggle=true)
                            instrumentation.runOnMainSync {observed=controller.preferences}
                            assertTrue(observed.smartWeights.enabled)
                            assertEquals(0.5,observed.smartWeights.strength,0.0)
                            assertEquals(0.05,observed.smartWeights.limit,0.0)
                            assertFalse(observed.smartWeights.skips)
                        } finally { instrumentation.runOnMainSync {controller.changePreferences(original)} }
                    }
                    "Playback" -> waitFor("Background playback")
                    "Diagnostics" -> waitFor("Playback debug mode")
                    "Logs" -> {waitFor("Search log");click("Tools");waitFor("Load saved history");click("Load saved history")}
                }
            }
        } finally { instrumentation.runOnMainSync {activity.finish()} }
    }
}
