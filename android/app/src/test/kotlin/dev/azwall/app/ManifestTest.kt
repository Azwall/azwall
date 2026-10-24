package dev.azwall.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestTest {
    /**
     * The browser's web views (open pages, dApp sessions) live in the activity: recreating it on a rotation, a
     * window resize or a theme switch would reload every tab and end every connection, so the activity handles them.
     */
    @Test
    fun theActivityIsNotRecreatedWhenTheScreenTurns() {
        val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val activity = manifest.getElementsByTagName("activity").item(0) as org.w3c.dom.Element
        val handled = activity.getAttributeNS("http://schemas.android.com/apk/res/android", "configChanges").split('|').toSet()
        for (change in listOf("orientation", "screenSize", "screenLayout", "smallestScreenSize", "uiMode")) {
            assertTrue("$change recreates the activity", change in handled)
        }
    }
}
