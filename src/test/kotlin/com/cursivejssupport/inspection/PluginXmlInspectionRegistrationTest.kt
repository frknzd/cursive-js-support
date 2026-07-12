package com.cursivejssupport.inspection

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class PluginXmlInspectionRegistrationTest {
    @Test
    fun `inspection short names are globally unique`() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/resources/META-INF/plugin.xml"))
        val inspections = document.getElementsByTagName("localInspection")
        val shortNames = (0 until inspections.length).map {
            inspections.item(it).attributes.getNamedItem("shortName").nodeValue
        }

        assertEquals(shortNames.distinct(), shortNames)
    }
}
