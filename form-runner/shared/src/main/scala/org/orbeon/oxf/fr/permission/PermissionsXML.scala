/**
 * Copyright (C) 2016 Orbeon, Inc.
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
package org.orbeon.oxf.fr.permission

import org.orbeon.saxon.om.NodeInfo
import org.orbeon.scaxon.SimplePath._
import org.orbeon.oxf.util.StringUtils._

import scala.xml.Elem


object PermissionsXML {

  def serialize(permissions: Permissions, normalized: Boolean): Option[Elem] =
    permissions match {
      case UndefinedPermissions =>
        None
      case DefinedPermissions(permissionsList) =>
        Some(
          <permissions>
            {permissionsList map (p =>
            <permission operations={Operations.serialize(p.operations, normalized).mkString(" ")}>
              {p.conditions map {
              case Owner => <owner/>
              case Group => <group-member/>
              case RolesAnyOf(roles) =>
                val escapedSpaces = roles.map(_.replace(" ", "%20"))
                val anyOfAttValue = escapedSpaces.mkString(" ")
                  <user-role any-of={anyOfAttValue}/>
            }}
            </permission>
            )}
          </permissions>
        )
    }

  def parse(permissionsElOrNull: NodeInfo): Permissions =
    Option(permissionsElOrNull) match {
      case None =>
        UndefinedPermissions
      case Some(permissionsEl) =>
        DefinedPermissions(permissionsEl.child("permission").toList.map(parsePermission))
    }

  /**
   * Given a permission element, e.g.:
   *
   *   <permission operations="read update delete">
   *
   * return the tokenized value of the `operations` attribute.
   *
   * See backward compatibility handling: https://github.com/orbeon/orbeon-forms/issues/5397
   */
  def permissionOperationsHandleList(permissionElement: NodeInfo): List[String] =
    Operations.normalizeOperations(permissionElement.attValue("operations")).to

  private def parsePermission(permissionEl: NodeInfo): Permission = {
    val operations = Operations.parse(permissionOperationsHandleList(permissionEl))
    val conditions =
      permissionEl.child(*).toList.map(
        conditionEl =>
          conditionEl.localname match {
            case "owner"        => Owner
            case "group-member" => Group
            case "user-role"    =>
              val anyOfAttValue = conditionEl.attValue("any-of")
              val rawRoles      = anyOfAttValue.splitTo[List](" ")
              val roles         = rawRoles.map(_.replace("%20", " "))
              RolesAnyOf(roles)
            case _ => throw new RuntimeException("")
          }
      )
    Permission(conditions, operations)
  }
}
