/**
 * Copyright (C) 2013 Orbeon, Inc.
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
package org.orbeon.oxf.fr.persistence.test

import org.orbeon.dom
import org.orbeon.dom.Document
import org.orbeon.oxf.externalcontext._
import org.orbeon.oxf.fr.permission.Operation.{Create, Delete, Read, Update}
import org.orbeon.oxf.fr.permission._
import org.orbeon.oxf.fr.persistence.db._
import org.orbeon.oxf.fr.persistence.http.{HttpAssert, HttpCall}
import org.orbeon.oxf.fr.persistence.relational.Provider
import org.orbeon.oxf.fr.persistence.relational.Version._
import org.orbeon.oxf.fr.workflow.definitions20201.Stage
import org.orbeon.oxf.test.{DocumentTestBase, ResourceManagerSupport, XFormsSupport, XMLSupport}
import org.orbeon.oxf.util.CoreUtils._
import org.orbeon.oxf.util.{CoreCrossPlatformSupport, IndentedLogger, LoggerFactory, Logging}
import org.orbeon.oxf.xml.dom.Converter._
import org.orbeon.oxf.xml.dom.IOSupport
import org.scalatest.funspec.AnyFunSpecLike

import java.io.ByteArrayInputStream
import scala.util.Random


/**
 * Test the persistence API (for now specifically the MySQL persistence layer), in particular:
 *      - Versioning
 *      - Drafts (used for autosave)
 *      - Permissions
 *      - Large XML documents and binary attachments
 */
class RestApiTest
  extends DocumentTestBase
     with AnyFunSpecLike
     with ResourceManagerSupport
     with XMLSupport
     with XFormsSupport
     with Logging {

  private implicit val Logger = new IndentedLogger(LoggerFactory.createLogger(classOf[RestApiTest]), true)
  private implicit val coreCrossPlatformSupport = CoreCrossPlatformSupport

  private val CanCreate           = SpecificOperations(Set(Create))
  private val CanRead             = SpecificOperations(Set(Read))
  private val CanUpdate           = SpecificOperations(Set(Update))
  private val CanCreateRead       = Operations.combine(CanCreate, CanRead)
  private val CanCreateReadUpdate = Operations.combine(CanCreateRead, CanUpdate)
  private val FormName = "my-form"

  private val AnyoneCanCreateAndRead = DefinedPermissions(List(Permission(Nil, SpecificOperations(Set(Read, Create)))))
  private val AnyoneCanCreate        = DefinedPermissions(List(Permission(Nil, SpecificOperations(Set(Create)))))

  private def createForm(provider: Provider)(implicit ec: ExternalContext): Unit = {
    val form = HttpCall.XML(buildFormDefinition(provider, permissions = UndefinedPermissions, title = Some("first")))
    val formURL = HttpCall.crudURLPrefix(provider) + "form/form.xhtml"
    HttpAssert.put(formURL, Unspecified, form, 201)
  }

  private def buildFormDefinition(
    provider     : Provider,
    permissions  : Permissions,
    title        : Option[String] = None
  ): Document =
    <xh:html xmlns:xh="http://www.w3.org/1999/xhtml" xmlns:xf="http://www.w3.org/2002/xforms">
      <xh:head>
        <xf:model id="fr-form-model">
          <xf:instance id="fr-form-metadata">
            <metadata>
              <application-name>{provider.entryName}</application-name>
              <form-name>{FormName}</form-name>
              <title xml:lang="en">{title.getOrElse("")}</title>
              { PermissionsXML.serialize(permissions, normalized = false).getOrElse("") }
            </metadata>
          </xf:instance>
        </xf:model>
      </xh:head>
    </xh:html>.toDocument

  describe("Form definition version") {
    it("must pass basic CRUD operations") {
      withTestExternalContext { implicit externalContext =>
        Connect.withOrbeonTables("form definition") { (_, provider) =>

          val formURL = HttpCall.crudURLPrefix(provider) + "form/form.xhtml"

          // First time we put with "latest" (AKA unspecified)
          val first = HttpCall.XML(<gaga1/>.toDocument)
          HttpAssert.put(formURL, Unspecified, first, 201)
          HttpAssert.get(formURL, Specific(1), HttpAssert.ExpectedBody (first, Operations.None, Some(1)))
          HttpAssert.get(formURL, Unspecified, HttpAssert.ExpectedBody (first, Operations.None, Some(1)))
          HttpAssert.get(formURL, Specific(2), HttpAssert.ExpectedCode(404))
          HttpAssert.del(formURL, Specific(2), 404)

          // Put again with "latest" (AKA unspecified) updates the current version
          val second = <gaga2/>.toDocument
          HttpAssert.put(formURL, Unspecified, HttpCall.XML(second), 201)
          HttpAssert.get(formURL, Specific(1), HttpAssert.ExpectedBody(HttpCall.XML(second), Operations.None, Some(1)))
          HttpAssert.get(formURL, Unspecified, HttpAssert.ExpectedBody(HttpCall.XML(second), Operations.None, Some(1)))
          HttpAssert.get(formURL, Specific(2), HttpAssert.ExpectedCode(404))

          // Put with "next" to get two versions
          val third = <gaga3/>.toDocument
          HttpAssert.put(formURL, Next, HttpCall.XML(third), 201)
          HttpAssert.get(formURL, Specific(1), HttpAssert.ExpectedBody(HttpCall.XML(second), Operations.None, Some(1)))
          HttpAssert.get(formURL, Specific(2), HttpAssert.ExpectedBody(HttpCall.XML(third),  Operations.None, Some(2)))
          HttpAssert.get(formURL, Unspecified, HttpAssert.ExpectedBody(HttpCall.XML(third),  Operations.None, Some(2)))
          HttpAssert.get(formURL, Specific(3), HttpAssert.ExpectedCode(404))

          // Put a specific version
          val fourth = <gaga4/>.toDocument
          HttpAssert.put(formURL, Specific(1), HttpCall.XML(fourth), 201)
          HttpAssert.get(formURL, Specific(1), HttpAssert.ExpectedBody(HttpCall.XML(fourth), Operations.None, Some(1)))
          HttpAssert.get(formURL, Specific(2), HttpAssert.ExpectedBody(HttpCall.XML(third),  Operations.None, Some(2)))
          HttpAssert.get(formURL, Unspecified, HttpAssert.ExpectedBody(HttpCall.XML(third),  Operations.None, Some(2)))
          HttpAssert.get(formURL, Specific(3), HttpAssert.ExpectedCode(404))

          // Delete the latest version
          HttpAssert.del(formURL, Unspecified, 204)
          HttpAssert.get(formURL, Specific(1), HttpAssert.ExpectedBody(HttpCall.XML(fourth), Operations.None, Some(1)))
          HttpAssert.get(formURL, Specific(2), HttpAssert.ExpectedCode(410))
          HttpAssert.get(formURL, Unspecified, HttpAssert.ExpectedBody(HttpCall.XML(fourth), Operations.None, Some(1)))

          // After a delete the version number is reused
          val fifth = <gaga5/>.toDocument
          HttpAssert.put(formURL, Next, HttpCall.XML(fifth), 201)
          HttpAssert.get(formURL, Specific(1), HttpAssert.ExpectedBody(HttpCall.XML(fourth), Operations.None, Some(1)))
          HttpAssert.get(formURL, Specific(2), HttpAssert.ExpectedBody(HttpCall.XML(fifth),  Operations.None, Some(2)))
          HttpAssert.get(formURL, Specific(3), HttpAssert.ExpectedCode(404))
        }
      }
    }
  }

  describe("Form data version and stage") {
    it("must pass basic operations") {
      withTestExternalContext { implicit externalContext =>
        Connect.withOrbeonTables("form data version") { (_, provider) =>

          val dataURL = HttpCall.crudURLPrefix(provider) + "data/123/data.xml"
          val data    = HttpCall.XML(<gaga1/>.toDocument)

          HttpAssert.put(dataURL, Specific(1), data, 404) // TODO: return 403 instead as reason is missing permissions!

          // 2023-04-18: Following changes to the persistence proxy: `PUT`ting data for a non-existing form definition
          // used to not fail, for some reason. Now we enforce the existence of a form definition so we can check
          // permissions. Hopefully, this is reasonable.
          createForm(provider)

          // Storing for specific form version
          val myStage = Some(Stage("my-stage", ""))
          HttpAssert.put(dataURL, Specific(1), data, 201)
          HttpAssert.get(dataURL, Unspecified, HttpAssert.ExpectedBody(data, AnyOperation, Some(1)))
          HttpAssert.put(dataURL, Specific(1), data, expectedCode = 201, stage = myStage)
          HttpAssert.get(dataURL, Unspecified, HttpAssert.ExpectedBody(data, AnyOperation, Some(1), stage = myStage))
          HttpAssert.del(dataURL, Unspecified, 204)
          HttpAssert.get(dataURL, Unspecified, HttpAssert.ExpectedCode(410))

          // Don't allow unspecified version for create
          HttpAssert.put(dataURL, Unspecified       , data, 400)
          HttpAssert.put(dataURL, Specific(1)       , data, 201)

          // Allow unspecified or correct version for update
          HttpAssert.put(dataURL, Unspecified      , data, 201)
          HttpAssert.put(dataURL, Specific(1)      , data, 201)

          // But don't allow incorrect version for update
          HttpAssert.put(dataURL, Specific(2)      , data, 400)

          // Fail with next/for document
          HttpAssert.put(dataURL, Next                               , data, 400)
          HttpAssert.put(dataURL, ForDocument("123", isDraft = false), data, 400)
        }
      }
    }
  }

  describe("Form definition corresponding to a document") {
    it("must pass basic operations") {
      withTestExternalContext { implicit externalContext =>
        Connect.withOrbeonTables("form data") { (_, provider) =>

          val formURL       = HttpCall.crudURLPrefix(provider) + "form/form.xhtml"
          val firstDataURL  = HttpCall.crudURLPrefix(provider) + "data/123/data.xml"
          val secondDataURL = HttpCall.crudURLPrefix(provider) + "data/456/data.xml"
          val first         = buildFormDefinition(provider, permissions = UndefinedPermissions, title = Some("first"))
          val second        = buildFormDefinition(provider, permissions = UndefinedPermissions, title = Some("second"))
          val data          = <gaga/>.toDocument

          HttpAssert.put(formURL      , Unspecified, HttpCall.XML(first) , 201)
          HttpAssert.put(formURL      , Next       , HttpCall.XML(second), 201)
          HttpAssert.put(firstDataURL , Specific(1), HttpCall.XML(data)  , 201)
          HttpAssert.put(secondDataURL, Specific(2), HttpCall.XML(data)  , 201)

          HttpAssert.get(formURL, ForDocument("123", isDraft = false), HttpAssert.ExpectedBody(HttpCall.XML(first) , Operations.None, Some(1)))
          HttpAssert.get(formURL, ForDocument("456", isDraft = false), HttpAssert.ExpectedBody(HttpCall.XML(second), Operations.None, Some(2)))
          HttpAssert.get(formURL, ForDocument("789", isDraft = false), HttpAssert.ExpectedCode(404))
        }
      }
    }
  }

  describe("Permissions") {
    it("must pass basic operations") {
      withTestExternalContext { implicit externalContext =>
        Connect.withOrbeonTables("permissions") { (_, provider) =>

          val formURL = HttpCall.crudURLPrefix(provider) + "form/form.xhtml"
          val data    = <data/>.toDocument
          val guest   = None
          val clerk   = Some(Credentials(UserAndGroup("tom", Some("clerk")  ), List(SimpleRole("clerk"  )), Nil))
          val manager = Some(Credentials(UserAndGroup("jim", Some("manager")), List(SimpleRole("manager")), Nil))
          val admin   = Some(Credentials(UserAndGroup("tim", Some("admin")  ), List(SimpleRole("admin"  )), Nil))

          locally {
            val DataURL = HttpCall.crudURLPrefix(provider) + "data/123/data.xml"

            // Anonymous: no permission defined
            HttpAssert.put(formURL, Unspecified, HttpCall.XML(buildFormDefinition(provider, UndefinedPermissions)), 201)
            HttpAssert.put(DataURL, Specific(1), HttpCall.XML(data), 201)
            HttpAssert.get(DataURL, Unspecified, HttpAssert.ExpectedBody(HttpCall.XML(data), AnyOperation, Some(1)))

            // Anonymous: create and read
            HttpAssert.put(formURL, Unspecified, HttpCall.XML(buildFormDefinition(provider, AnyoneCanCreateAndRead)), 201)
            HttpAssert.get(DataURL, Unspecified, HttpAssert.ExpectedBody(HttpCall.XML(data), SpecificOperations(Set(Create, Read)), Some(1)))

            // Anonymous: just create, then can't read data
            HttpAssert.put(formURL, Unspecified, HttpCall.XML(buildFormDefinition(provider, AnyoneCanCreate)), 201)
            HttpAssert.get(DataURL, Unspecified, HttpAssert.ExpectedCode(403))
          }

          locally {
            val DataURL = HttpCall.crudURLPrefix(provider) + "data/456/data.xml"

            // More complex permissions based on roles
            HttpAssert.put(formURL, Unspecified, HttpCall.XML(buildFormDefinition(provider, DefinedPermissions(List(
              Permission(Nil                              , SpecificOperations(Set(Create))),
              Permission(List(RolesAnyOf(List("clerk"  ))), SpecificOperations(Set(Read))),
              Permission(List(RolesAnyOf(List("manager"))), SpecificOperations(Set(Read, Update))),
              Permission(List(RolesAnyOf(List("admin"  ))), SpecificOperations(Set(Read, Update, Delete)))
            )))), 201)
            HttpAssert.put(DataURL, Specific(1), HttpCall.XML(data), 201)

            // Check who can read
            HttpAssert.get(DataURL, Unspecified, HttpAssert.ExpectedCode(403)                                                                               , guest)
            HttpAssert.get(DataURL, Unspecified, HttpAssert.ExpectedBody(HttpCall.XML(data), SpecificOperations(Set(Create, Read))                , Some(1)), clerk)
            HttpAssert.get(DataURL, Unspecified, HttpAssert.ExpectedBody(HttpCall.XML(data), SpecificOperations(Set(Create, Read, Update))        , Some(1)), manager)
            HttpAssert.get(DataURL, Unspecified, HttpAssert.ExpectedBody(HttpCall.XML(data), SpecificOperations(Set(Create, Read, Update, Delete)), Some(1)), admin)

            // Only managers and admins can update
            HttpAssert.put(DataURL, Unspecified, HttpCall.XML(data), 403, guest)
            HttpAssert.put(DataURL, Unspecified, HttpCall.XML(data), 403, clerk)
            HttpAssert.put(DataURL, Unspecified, HttpCall.XML(data), 201, manager)
            HttpAssert.put(DataURL, Unspecified, HttpCall.XML(data), 201, admin)

            // Only admins can delete
            HttpAssert.del(DataURL, Unspecified, 403, guest)
            HttpAssert.del(DataURL, Unspecified, 403, clerk)
            HttpAssert.del(DataURL, Unspecified, 403, manager)
            HttpAssert.del(DataURL, Unspecified, 204, admin)

            // Always return a 404 if the data doesn't exist, irrelevant of the permissions
            // 2023-04-18: Following changes to the persistence proxy: this is now a 410.
            // https://github.com/orbeon/orbeon-forms/issues/4979#issuecomment-912742633
            HttpAssert.del(DataURL, Unspecified, 410, guest)
            HttpAssert.del(DataURL, Unspecified, 410, clerk)
            HttpAssert.del(DataURL, Unspecified, 410, manager)
            HttpAssert.del(DataURL, Unspecified, 410, admin)
          }
        }
      }
    }
  }

  describe("Organizations") {
    ignore("must pass basic operations") {
      withTestExternalContext { implicit externalContext =>
        Connect.withOrbeonTables("Organization-based permissions") { (_, provider) =>

          val formURL   = HttpCall.crudURLPrefix(provider) + "form/form.xhtml"

          // Users
          val c1User   = Some(Credentials(UserAndGroup("c1User"  , None), Nil, List(Organization(List("a", "b", "c")))))
          val c2User   = Some(Credentials(UserAndGroup("c2User"  , None), Nil, List(Organization(List("a", "b", "c")))))
          val cManager = Some(Credentials(UserAndGroup("cManager", None), List(ParametrizedRole("manager", "c")), Nil))
          val bManager = Some(Credentials(UserAndGroup("cManager", None), List(ParametrizedRole("manager", "b")), Nil))
          val dManager = Some(Credentials(UserAndGroup("cManager", None), List(ParametrizedRole("manager", "d")), Nil))

          val dataURL   = HttpCall.crudURLPrefix(provider) + "data/123/data.xml"
          val dataBody  = HttpCall.XML(<gaga/>.toDocument)

          // User can read their own data, as well as their managers
          HttpAssert.put(formURL, Unspecified, HttpCall.XML(buildFormDefinition(provider, DefinedPermissions(List(
            Permission(Nil                              , SpecificOperations(Set(Create))),
            Permission(List(Owner)                      , SpecificOperations(Set(Read, Update))),
            Permission(List(RolesAnyOf(List("clerk")))  , SpecificOperations(Set(Read))),
            Permission(List(RolesAnyOf(List("manager"))), SpecificOperations(Set(Read, Update)))
          )))), 201)

          // Data initially created by sfUserA
          HttpAssert.put(dataURL, Specific(1), dataBody, 201, c1User)
          // Owner can read their own data
          HttpAssert.get(dataURL, Unspecified, HttpAssert.ExpectedBody(dataBody, CanCreateReadUpdate, Some(1)), c1User)
          // Other users can't read the data
          HttpAssert.get(dataURL, Unspecified, HttpAssert.ExpectedCode(403)                                   , c2User)
          // Managers of the user up the organization structure can read the data
          HttpAssert.get(dataURL, Unspecified, HttpAssert.ExpectedBody(dataBody, CanCreateReadUpdate, Some(1)), cManager) //TODO: getting 403
          HttpAssert.get(dataURL, Unspecified, HttpAssert.ExpectedBody(dataBody, CanCreateReadUpdate, Some(1)), bManager)
          // Other managers can't read the data
          HttpAssert.get(dataURL, Unspecified, HttpAssert.ExpectedCode(403)                                   , dManager)
        }
      }
    }
  }

  // Try uploading files of 1 KB, 1 MB
  describe("Attachments") {
    it("must pass basic operations") {
      withTestExternalContext { implicit externalContext =>
        Connect.withOrbeonTables("attachments") { (_, provider) =>

          createForm(provider)

          for ((size, position) <- Seq(1024, 1024 * 1024).zipWithIndex) {
            val bytes =  new Array[Byte](size) |!> Random.nextBytes |> HttpCall.Binary
            val dataUrl = HttpCall.crudURLPrefix(provider) + "data/123/file" + position.toString
            HttpAssert.put(dataUrl, Specific(1), bytes, 201)
            HttpAssert.get(dataUrl, Unspecified, HttpAssert.ExpectedBody(bytes, AnyOperation, Some(1)))
          }
        }
      }
    }
  }

  // Try uploading files of 1 KB, 1 MB
  describe("Large XML documents") {
    it("must pass basic operations") {
      withTestExternalContext { implicit externalContext =>
        Connect.withOrbeonTables("large XML documents") { (_, provider) =>

          createForm(provider)

          for ((size, position) <- Seq(1024, 1024 * 1024).zipWithIndex) {

            val charArray = new Array[Char](size)
            for (i <- 0 until size)
              charArray(i) = Random.nextPrintableChar()

            val text    = dom.Text(new String(charArray))
            val element = dom.Element("gaga")   |!> (_.add(text))
            val xmlBody = dom.Document(element) |> HttpCall.XML

            val dataUrl = HttpCall.crudURLPrefix(provider) + s"data/$position/data.xml"

            HttpAssert.put(dataUrl, Specific(1), xmlBody, 201)
            HttpAssert.get(dataUrl, Unspecified, HttpAssert.ExpectedBody(xmlBody, AnyOperation, Some(1)))
          }
        }
      }
    }
  }

  describe("Drafts") {
    it("must pass basic operations") {
      withTestExternalContext { implicit externalContext =>
        Connect.withOrbeonTables("drafts") { (_, provider) =>

          createForm(provider)

          // Draft and non-draft are different
          val first    = HttpCall.XML(<gaga1/>.toDocument)
          val second   = HttpCall.XML(<gaga2/>.toDocument)
          val dataURL  = HttpCall.crudURLPrefix(provider) + "data/123/data.xml"
          val draftURL = HttpCall.crudURLPrefix(provider) + "draft/123/data.xml"

          HttpAssert.put(dataURL,  Specific(1), first, 201)

          println(s"xxx before error")

          // 2023-04-18: Following changes to the persistence proxy: we now get a 400 instead of a 201. It seems
          // unreasonable that storing a draft would succeed without passing a version, if it's the first time we are
          // storing the draft for the given document id.
          HttpAssert.put(draftURL, Unspecified, second, 400)
          HttpAssert.put(draftURL, Specific(1), second, 201) // this on the other hand succeeds
          HttpAssert.get(dataURL,  Unspecified, HttpAssert.ExpectedBody(first, AnyOperation, Some(1)))
          HttpAssert.get(draftURL, Unspecified, HttpAssert.ExpectedBody(second, AnyOperation, Some(1)))
        }
      }
    }
  }

  describe("Metadata extraction") {
    it("must pass basic operations") {
      withTestExternalContext { implicit externalContext =>
        Connect.withOrbeonTables("extract metadata") { (_, provider) =>

          val currentFormURL        = HttpCall.crudURLPrefix(provider) + "form/form.xhtml"
          val currentMetadataURL    = HttpCall.metadataURL(provider)
          val formDefinition        = buildFormDefinition(provider, AnyoneCanCreateAndRead)

          HttpAssert.put(currentFormURL, Unspecified, HttpCall.XML(formDefinition), 201)

          val expectedBody =
            <forms>
                <form operations="create read">
                    <application-name>{provider.entryName}</application-name>
                    <form-name>my-form</form-name>
                    <form-version>1</form-version>
                    <title xml:lang="en"/>
                    <permissions>
                        <permission operations="create read -list"/>
                    </permissions>
                </form>
            </forms>.toDocument

          val (resultCode, _, resultBodyTry) = HttpCall.get(currentMetadataURL, Unspecified, None)

          assert(resultCode === 200)

          def filterResultBody(bytes: Array[Byte]) = {

            val doc = IOSupport.readOrbeonDom(new ByteArrayInputStream(bytes))

            for {
              formElem             <- doc.getRootElement.elements("form")
              lastModifiedTimeElem <- formElem.elements("last-modified-time")
            } locally {
              lastModifiedTimeElem.detach()
            }

            doc
          }

          assertXMLDocumentsIgnoreNamespacesInScope(filterResultBody(resultBodyTry.get), expectedBody)
        }
      }
    }
  }
}
