/**
  * Copyright (C) 2018 Orbeon, Inc.
  *
  * This program is free software; you can redistribute it and/or modify it under the terms of the
  * GNU Lesser General Public License as published by the Free Software Foundation; either version
  *  2.1 of the License, or (at your option) any later version.
  *
  * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
  * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
  * See the GNU Lesser General Public License for more details.
  *
  * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
  */
package org.orbeon.oxf.fr

import enumeratum.EnumEntry.Lowercase
import cats.syntax.option._
import org.log4s
import org.orbeon.oxf.fr.datamigration.MigrationSupport
import org.orbeon.oxf.http.StatusCode
import org.orbeon.oxf.util.LoggerFactory
import org.orbeon.oxf.xforms.{XFormsContainingDocument, XFormsStaticState}
import org.orbeon.oxf.xforms.action.XFormsAPI.{delete, inScopeContainingDocument, insert}
import org.orbeon.oxf.xforms.analysis.model.StaticBind
import org.orbeon.oxf.xforms.model.XFormsModel
import org.orbeon.saxon.om
import org.orbeon.scaxon.Implicits._
import org.orbeon.scaxon.SimplePath._
import org.orbeon.xforms.XFormsId
import shapeless.syntax.typeable._


object SimpleDataMigration {

  import Private._

  import enumeratum._

  sealed trait DataMigrationBehavior extends EnumEntry with Lowercase

  object DataMigrationBehavior extends Enum[DataMigrationBehavior] {

    val values = findValues

    case object Enabled   extends DataMigrationBehavior
    case object Disabled  extends DataMigrationBehavior
    case object HolesOnly extends DataMigrationBehavior
    case object Error     extends DataMigrationBehavior
  }

  sealed trait DataMigrationOp

  object DataMigrationOp {
    case class Insert(parentElem: om.NodeInfo, after: Option[String], template: Option[om.NodeInfo]) extends DataMigrationOp
    case class Delete(elem: om.NodeInfo)                                                             extends DataMigrationOp
  }

  trait FormOps {

    type DocType
    type BindType

    def findFormBindsRoot: Option[BindType]
    def templateIterationNamesToRootElems: Map[String, om.NodeInfo]
    def bindChildren(bind: BindType): List[BindType]
    def bindNameOpt(bind: BindType): Option[String]
  }

  // Attempt to fill/remove holes in an instance given:
  //
  // - the enclosing model
  // - the main template instance
  // - the data to update
  //
  // The function returns the root element of the updated data if there was any update, or the
  // empty sequence if there was no data to update.
  //
  //@XPathFunction
  def dataMaybeWithSimpleMigration(
    enclosingModelAbsoluteId : String,
    templateInstanceRootElem : om.NodeInfo,
    dataToMigrateRootElem    : om.NodeInfo
  ): Option[om.NodeInfo] = {

    val doc = inScopeContainingDocument

    val maybeMigrated =
      dataMaybeWithSimpleMigrationUseOps(
        enclosingModelAbsoluteId = enclosingModelAbsoluteId,
        templateInstanceRootElem = templateInstanceRootElem,
        dataToMigrateRootElem    = dataToMigrateRootElem,
        dataMigrationBehavior    = getConfiguredDataMigrationBehavior(doc.staticState))(
        formOps                  = new ContainingDocumentOps(inScopeContainingDocument, enclosingModelAbsoluteId)
      )

    maybeMigrated map {
      case Left(_)  => FormRunner.sendError(StatusCode.InternalServerError) // TODO: Which error is best?
      case Right(v) => v
    }
  }

  def dataMaybeWithSimpleMigrationUseOps(
    enclosingModelAbsoluteId : String,
    templateInstanceRootElem : om.NodeInfo,
    dataToMigrateRootElem    : om.NodeInfo,
    dataMigrationBehavior    : DataMigrationBehavior)(implicit
    formOps                  : FormOps
  ): Option[List[DataMigrationOp] Either om.NodeInfo] = {

    require(XFormsId.isAbsoluteId(enclosingModelAbsoluteId))
    require(templateInstanceRootElem.isElement)
    require(dataToMigrateRootElem.isElement)

    dataMigrationBehavior match {
      case DataMigrationBehavior.Disabled =>
        None
      case DataMigrationBehavior.Enabled | DataMigrationBehavior.HolesOnly =>

        val dataToMigrateRootElemMutable =
          MigrationSupport.copyDocumentKeepInstanceData(dataToMigrateRootElem.root).rootElement

        val ops =
          gatherMigrationOps(
            enclosingModelAbsoluteId = enclosingModelAbsoluteId,
            templateInstanceRootElem = templateInstanceRootElem,
            dataToMigrateRootElem    = dataToMigrateRootElemMutable
          )

        lazy val deleteOps =
          ops collect {
            case delete: DataMigrationOp.Delete => delete
          }

        val mustMigrate =
          ops.nonEmpty && (
            dataMigrationBehavior == DataMigrationBehavior.Enabled ||
            dataMigrationBehavior == DataMigrationBehavior.HolesOnly && deleteOps.isEmpty
          )

        val mustRaiseError =
          ops.nonEmpty && dataMigrationBehavior == DataMigrationBehavior.HolesOnly && deleteOps.nonEmpty

        if (mustMigrate) {
          performMigrationOps(ops)
          Some(Right(dataToMigrateRootElemMutable))
        } else if (mustRaiseError) {
          Left(deleteOps).some
        } else {
          None
        }

      case DataMigrationBehavior.Error =>

        val ops =
          gatherMigrationOps(
            enclosingModelAbsoluteId = enclosingModelAbsoluteId,
            templateInstanceRootElem = templateInstanceRootElem,
            dataToMigrateRootElem    = dataToMigrateRootElem
          )

        if (ops.nonEmpty)
          Left(ops).some
        else
          None
    }
  }

  // This is used in `form-to-xbl.xsl`, see:
  // https://github.com/orbeon/orbeon-forms/issues/3829
  //@XPathFunction
  def iterateBinds(
    enclosingModelAbsoluteId : String,
    dataRootElem             : om.NodeInfo
  ): om.SequenceIterator = {

    val ops = new ContainingDocumentOps(inScopeContainingDocument, enclosingModelAbsoluteId)

    def processLevel(
      parents          : List[om.NodeInfo],
      binds            : Seq[StaticBind],
      path             : List[String]
    ): List[om.NodeInfo] = {

      def findOps(prevBindOpt: Option[StaticBind], bind: StaticBind, bindName: String): List[om.NodeInfo] =
        parents flatMap { parent =>

          val nestedElems =
            parent / bindName toList

          nestedElems ::: processLevel(
            parents = nestedElems,
            binds   = bind.childrenBinds,
            path    = bindName :: path
          )
        }

      scanBinds(ops)(binds, findOps)
    }

    // The root bind has id `fr-form-binds` at the top-level as well as within section templates
    ops.findFormBindsRoot.toList flatMap { bind =>
      processLevel(
        parents = List(dataRootElem),
        binds   = bind.childrenBinds,
        path    = Nil
      )
    }
  }

  private object Private {

    val logger: log4s.Logger = LoggerFactory.createLogger("org.orbeon.fr.data-migration")

    val DataMigrationFeatureName  = "data-migration"
    val DataMigrationPropertyName = s"oxf.fr.detail.$DataMigrationFeatureName"

    def getConfiguredDataMigrationBehavior(staticState: XFormsStaticState): DataMigrationBehavior = {

      implicit val formRunnerParams = FormRunnerParams()

      val behavior =
        if (FormRunner.isDesignTime)
          DataMigrationBehavior.Disabled
        else
          FormRunner.metadataInstance map (_.rootElement)                          flatMap
          (FormRunner.optionFromMetadataOrProperties(_, DataMigrationFeatureName)) flatMap
          DataMigrationBehavior.withNameOption                                     getOrElse
          DataMigrationBehavior.Disabled

      def isFeatureEnabled =
        staticState.isPEFeatureEnabled(
          featureRequested = true,
          "Form Runner simple data migration"
        )

      if ((behavior == DataMigrationBehavior.Enabled || behavior == DataMigrationBehavior.HolesOnly) && ! isFeatureEnabled)
        DataMigrationBehavior.Error
      else
        behavior
    }

    def scanBinds[T](
      ops   : FormOps)(
      binds : Seq[ops.BindType],
      find  : (Option[ops.BindType], ops.BindType, String) => List[T]
    ): List[T] = {

      var result: List[T] = Nil

      binds.scanLeft(None: Option[ops.BindType]) { case (prevBindOpt, bind) =>
        ops.bindNameOpt(bind) foreach { bindName =>
          result = find(prevBindOpt, bind, bindName) ::: result
        }
        Some(bind)
      }

      result
    }

    class ContainingDocumentOps(doc: XFormsContainingDocument, enclosingModelAbsoluteId: String) extends FormOps {

      type DocType = XFormsContainingDocument
      type BindType = StaticBind

      private val enclosingModel =
        doc.findObjectByEffectiveId(XFormsId.absoluteIdToEffectiveId(enclosingModelAbsoluteId)) flatMap
          (_.narrowTo[XFormsModel])                                                             getOrElse
          (throw new IllegalStateException)

      def findFormBindsRoot: Option[StaticBind] =
        enclosingModel.staticModel.bindsById.get(Names.FormBinds)

      def templateIterationNamesToRootElems: Map[String, om.NodeInfo] =
        (
          for {
            instance   <- enclosingModel.instancesIterator
            instanceId = instance.getId
            if FormRunner.isTemplateId(instanceId)
          } yield
            instance.rootElement.localname -> instance.rootElement
        ).toMap

      def bindChildren(bind: StaticBind): List[StaticBind] =
        bind.childrenBinds

      def bindNameOpt(bind: StaticBind): Option[String] =
        bind.nameOpt
    }

    def gatherMigrationOps(
      enclosingModelAbsoluteId : String,
      templateInstanceRootElem : om.NodeInfo,
      dataToMigrateRootElem    : om.NodeInfo)(implicit
      formOps                  : FormOps
    ): List[DataMigrationOp] = {

      val templateIterationNamesToRootElems = formOps.templateIterationNamesToRootElems

      // How this works:
      //
      // - The source of truth is the bind tree.
      // - We iterate binds from root to leaf.
      // - Repeated elements are identified by the existence of a template instance, so
      //   we don't need to look at the static tree of controls.
      // - Element templates are searched first in the form instance and then, as we enter
      //   repeats, the relevant template instances.
      // - We use the bind hierarchy to look for templates, instead of just searching for the first
      //   matching element, because the top-level instance can contain data from section templates,
      //   and those are not guaranteed to be unique. Se we could find an element template coming
      //   from section template data, which would be the wrong element template. By following binds,
      //   and taking paths from them, we avoid finding incorrect element templates in section template
      //   data.
      // - NOTE: We never need to identify a template for a repeat iteration, because repeat
      //   iterations are optional!

      def findElementTemplate(templateRootElem: om.NodeInfo, path: List[String]): Option[om.NodeInfo] =
        path.foldRight(Option(templateRootElem)) {
          case (_, None)          => None
          case (name, Some(node)) => node firstChildOpt name
        }

      // NOTE: We work with `List`, which is probably the most optimal thing. Tried with `Iterator` but
      // it is messy and harder to get right.
      def processLevel(
        parents          : List[om.NodeInfo],
        binds            : List[formOps.BindType], // use `List` to ensure eager evaluation
        templateRootElem : om.NodeInfo,
        path             : List[String]
      ): List[DataMigrationOp] = {

        val allBindNames = binds.flatMap(formOps.bindNameOpt).toSet

        def findOps(prevBindOpt: Option[formOps.BindType], bind: formOps.BindType, bindName: String): List[DataMigrationOp] = {

          parents flatMap { parent =>
            parent / bindName toList match {
              case Nil =>
                List(
                  DataMigrationOp.Insert(
                    parentElem = parent,
                    after      = prevBindOpt.flatMap(formOps.bindNameOpt),
                    template   = findElementTemplate(templateRootElem, bindName :: path)
                  )
                )
              case nodes =>

                // Recurse
                val newTemplateRootElem =
                  templateIterationNamesToRootElems.get(bindName)

                processLevel(
                  parents          = nodes,
                  binds            = formOps.bindChildren(bind),
                  templateRootElem = newTemplateRootElem getOrElse templateRootElem,
                  path             = if (newTemplateRootElem.isDefined) Nil else bindName :: path
                )
            }
          }
        }

        // https://github.com/orbeon/orbeon-forms/issues/5041
        // Only delete if there are no nested bind, for backward compatibility. But is this wrong? IF we don't do this,
        // we will delete `<_>` for multiple attachments as well as the nested grids in section template data. However,
        // this probably also means that we'll not delete extra elements nested within other leaves.
        val deleteOps =
          if (binds.nonEmpty)
            parents / * filter (e => ! allBindNames(e.localname)) map { e =>
              DataMigrationOp.Delete(e)
            }
          else
            Nil

        deleteOps ++: scanBinds(formOps)(binds, findOps)
      }

      // The root bind has id `fr-form-binds` at the top-level as well as within section templates
      formOps.findFormBindsRoot.toList flatMap { bind =>
        processLevel(
          parents          = List(dataToMigrateRootElem),
          binds            = formOps.bindChildren(bind),
          templateRootElem = templateInstanceRootElem,
          path             = Nil
        )
      }
    }

    def performMigrationOps(migrationOps: List[DataMigrationOp]): Unit =
      migrationOps foreach {
        case DataMigrationOp.Delete(elem) =>

          logger.debug(s"removing element `${elem.localname}` from `${elem.getParent.localname}`")
          delete(elem)

        case DataMigrationOp.Insert(parentElem, after, Some(template)) =>

          logger.debug(s"inserting element `${template.localname}` into `${parentElem.localname}` after `$after`")

          insert(
            into   = parentElem,
            after  = after.toList flatMap (parentElem / _),
            origin = template.toList
          )

        case DataMigrationOp.Insert(_, _, None) =>

          // Template for the element was not found. Error?
      }
  }
}
