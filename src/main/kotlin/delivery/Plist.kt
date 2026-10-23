package delivery

import java.io.StringReader
import java.time.Instant
import java.util.Base64
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

// External property-list representation, confined to signing and artifact boundaries.
sealed interface PlistValue

data class PlistDict(val values: Map<String, PlistValue>) : PlistValue {
    fun string(key: String) = (values.getValue(key) as PlistString).value

    fun bool(key: String) = (values[key] as? PlistBoolean)?.value ?: false

    fun dict(key: String) = values.getValue(key) as PlistDict

    fun array(key: String) = (values.getValue(key) as PlistArray).values
}

data class PlistString(val value: String) : PlistValue

data class PlistBoolean(val value: Boolean) : PlistValue

data class PlistArray(val values: List<PlistValue>) : PlistValue

data class PlistDate(val value: Instant) : PlistValue

class PlistData(val value: ByteArray) : PlistValue

fun xmlFactory(): DocumentBuilderFactory =
    DocumentBuilderFactory.newInstance().apply {
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }

fun parsePlist(xml: String): PlistDict {
    fun children(element: Element): List<Element> =
        (0 until element.childNodes.length).mapNotNull { element.childNodes.item(it) as? Element }
    fun parse(element: Element): PlistValue =
        when (element.tagName) {
            "dict" ->
                PlistDict(
                    children(element).chunked(2).associate { (key, value) ->
                        key.textContent to parse(value)
                    }
                )
            "array" -> PlistArray(children(element).map { parse(it) })
            "true" -> PlistBoolean(true)
            "false" -> PlistBoolean(false)
            "date" -> PlistDate(Instant.parse(element.textContent.trim()))
            "data" -> PlistData(Base64.getMimeDecoder().decode(element.textContent))
            "string",
            "integer",
            "real" -> PlistString(element.textContent)
            else -> error("Unsupported plist element: ${element.tagName}")
        }
    val root =
        xmlFactory().newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
    return parse(children(root).single()) as PlistDict
}
