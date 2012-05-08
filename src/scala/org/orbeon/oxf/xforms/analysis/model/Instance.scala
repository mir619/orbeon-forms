/**
 * Copyright (C) 2010 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms.analysis.model


import org.orbeon.oxf.processor.ProcessorImpl
import org.orbeon.oxf.util.NetUtils
import org.orbeon.oxf.xforms._
import analysis.{StaticStateContext, SimpleElementAnalysis, ElementAnalysis}
import xbl.Scope
import org.orbeon.oxf.util.Connection.Credentials
import XFormsConstants._
import org.dom4j.{QName, Element}
import org.orbeon.oxf.xml.dom4j.ExtendedLocationData
import org.orbeon.oxf.common.{ValidationException, Version}
import org.orbeon.oxf.xml.{ContentHandlerHelper, Dom4j}

/**
 * Static analysis of an XForms instance.
 */
class Instance(staticStateContext: StaticStateContext, element: Element, parent: Option[ElementAnalysis], preceding: Option[ElementAnalysis], scope: Scope)
        extends SimpleElementAnalysis(staticStateContext, element, parent, preceding, scope) {

    val isReadonlyHint = XFormsInstance.isReadonlyHint(element)
    val isCacheHint = Version.instance.isPEFeatureEnabled(XFormsInstance.isCacheHint(element), "cached XForms instance")
    val xxformsTimeToLive = XFormsInstance.getTimeToLive(element)
    val xxformsValidation = element.attributeValue(XXFORMS_VALIDATION_QNAME)

    val credentials = {
        // NOTE: AVTs not supported because XPath expressions in those could access instances that haven't been loaded
        def username = element.attributeValue(XXFORMS_USERNAME_QNAME)
        def password = element.attributeValue(XXFORMS_PASSWORD_QNAME)
        def preemptiveAuthentication = element.attributeValue(XXFORMS_PREEMPTIVE_AUTHENTICATION_QNAME)
        def domain = element.attributeValue(XXFORMS_DOMAIN_QNAME)

        if (username ne null)
            new Credentials(username, password, preemptiveAuthentication, domain)
        else
            null
    }

    val excludeResultPrefixes = element.attributeValue(XXFORMS_EXCLUDE_RESULT_PREFIXES)

    // Inline root element if any
    val root = Dom4j.elements(element) headOption
    private def hasInlineContent = root.isDefined

    // Don't allow more than one child element
    if (Dom4j.elements(element).size > 1)
        throw new ValidationException("xforms:instance must contain at most one child element", extendedLocationData)

    private def getAttributeEncode(qName: QName) = Option(element.attributeValue(qName)) map (att ⇒ NetUtils.encodeHRRI(att.trim, true))

    private def src = getAttributeEncode(SRC_QNAME)
    private def resource = getAttributeEncode(RESOURCE_QNAME)

    // @src always wins, @resource always loses
    val useInlineContent = ! src.isDefined && hasInlineContent
    val useExternalContent = src.isDefined || ! hasInlineContent && resource.isDefined

    val (instanceSource, dependencyURL) =
        (if (useInlineContent) None else src orElse resource) match {
            case someSource @ Some(source) if ProcessorImpl.isProcessorOutputScheme(source) ⇒
                someSource → None // input:* doesn't add a URL dependency, but is handled by the pipeline engine
            case someSource @ Some(_) ⇒
                someSource → someSource
            case _ ⇒
                None → None
        }

    // Don't allow a blank src attribute
    if (useExternalContent && instanceSource == Some(""))
        throw new ValidationException("xforms:instance must not specify a blank URL", extendedLocationData)

    private def extendedLocationData = new ExtendedLocationData(locationData, "processing XForms instance", element, "id", staticId)

    // For now we don't want to see instances printed as controls in unit tests
    override def toXML(helper: ContentHandlerHelper, attributes: List[String])(content: ⇒ Unit) = ()
}
