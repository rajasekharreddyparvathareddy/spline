/*
 * Copyright 2019 ABSA Group Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package za.co.absa.spline.admin

import org.backuity.ansi.AnsiFormatter.FormattedHelper
import scopt.{OptionDef, OptionParser}
import za.co.absa.spline.admin.AdminCLI.AdminCLIConfig
import za.co.absa.spline.common.SplineBuildInfo
import za.co.absa.spline.persistence.ArangoConnectionURL.{ArangoDbScheme, ArangoSecureDbScheme}
import za.co.absa.spline.persistence.OnDBExistsAction.{Drop, Fail, Skip}
import za.co.absa.spline.persistence.{ArangoConnectionURL, ArangoManagerFactory, ArangoManagerFactoryImpl}

import scala.concurrent.Await
import scala.concurrent.duration._

object AdminCLI extends App {

  import scala.concurrent.ExecutionContext.Implicits._

  case class AdminCLIConfig(cmd: Command = null)

  private val dbManagerFactory = new ArangoManagerFactoryImpl()
  new AdminCLI(dbManagerFactory).exec(args)
}

class AdminCLI(dbManagerFactory: ArangoManagerFactory) {

  def exec(args: Array[String]): Unit = {

    val cliParser: OptionParser[AdminCLIConfig] = new OptionParser[AdminCLIConfig]("Spline Admin CLI") {
      head("Spline Admin Command Line Interface", SplineBuildInfo.Version)

      help("help").text("prints this usage text")

      def dbCommandOptions: Seq[OptionDef[_, AdminCLIConfig]] = Seq(
        opt[String]('t', "timeout")
          text s"Timeout in format `<length><unit>` or `Inf` for infinity. Default is ${DBInit().timeout}"
          action { case (s, c@AdminCLIConfig(cmd: DBCommand)) => c.copy(cmd.timeout = Duration(s)) },
        opt[Unit]('k', "insecure")
          text s"Allow insecure server connections when using SSL; disallowed by default"
          action { case (_, c@AdminCLIConfig(cmd: DBCommand)) => c.copy(cmd.insecure = true) },
        arg[String]("<db_url>")
          required()
          text s"ArangoDB connection string in the format: $ArangoDbScheme|$ArangoSecureDbScheme://[user[:password]@]host[:port]/database"
          action { case (url, c@AdminCLIConfig(cmd: DBCommand)) => c.copy(cmd.dbUrl = url) })

      (cmd("db-init")
        action ((_, c) => c.copy(cmd = DBInit()))
        text "Initialize Spline database"
        children (dbCommandOptions: _*)
        children(
        opt[Unit]('f', "force")
          text "Re-create the database if one already exists"
          action { case (_, c@AdminCLIConfig(cmd: DBInit)) => c.copy(cmd.copy(force = true)) },
        opt[Unit]('s', "skip")
          text "Skip existing database. Don't throw error, just end"
          action { case (_, c@AdminCLIConfig(cmd: DBInit)) => c.copy(cmd.copy(skip = true)) }
      ))

      (cmd("db-upgrade")
        action ((_, c) => c.copy(cmd = DBUpgrade()))
        text "Upgrade Spline database"
        children (dbCommandOptions: _*))

      checkConfig {
        case AdminCLIConfig(null) =>
          failure("No command given")
        case AdminCLIConfig(cmd: DBCommand) if cmd.dbUrl == null =>
          failure("DB connection string is required")
        case AdminCLIConfig(cmd: DBCommand) if cmd.dbUrl.startsWith(ArangoSecureDbScheme) && !cmd.insecure =>
          failure("At the moment, only unsecure SSL is supported; when using the secure scheme, please add the -k option to skip server certificate verification altogether")
        case AdminCLIConfig(cmd: DBInit) if cmd.force && cmd.skip =>
          failure("Options '--force' and '--skip' cannot be used together")
        case _ =>
          success
      }
    }

    val command = cliParser
      .parse(args, AdminCLIConfig())
      .getOrElse(sys.exit(1))
      .cmd

    command match {
      case DBInit(url, timeout, _, force, skip) =>
        val onExistsAction = (force, skip) match {
          case (true, false) => Drop
          case (false, true) => Skip
          case (false, false) => Fail
        }
        val dbManager = dbManagerFactory.create(ArangoConnectionURL(url))
        val wasInitialized = Await.result(dbManager.initialize(onExistsAction), timeout)
        if (!wasInitialized) println(ansi"%yellow{Skipped. DB is already initialized}")

      case DBUpgrade(url, timeout, _) =>
        val dbManager = dbManagerFactory.create(ArangoConnectionURL(url))
        Await.result(dbManager.upgrade(), timeout)
    }

    println(ansi"%green{DONE}")
  }
}
