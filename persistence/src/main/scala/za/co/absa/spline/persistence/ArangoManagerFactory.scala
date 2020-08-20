/*
 * Copyright 2020 ABSA Group Limited
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

package za.co.absa.spline.persistence

import com.arangodb.async.ArangoDatabaseAsync
import za.co.absa.spline.persistence.migration.{MigrationScriptRepository, Migrator}

import scala.concurrent.ExecutionContext

trait ArangoManagerFactory {
  def create(connectionURL: ArangoConnectionURL): ArangoManager
}

class ArangoManagerFactoryImpl()(implicit ec: ExecutionContext) extends ArangoManagerFactory {

  override def create(connectionURL: ArangoConnectionURL): ArangoManager = {
    val scriptRepo = MigrationScriptRepository

    def dbManagerFactory(db: ArangoDatabaseAsync): ArangoManagerImpl = {
      val versionManager = new DatabaseVersionManager(db)
      val migrator = new Migrator(db, scriptRepo, versionManager)
      new ArangoManagerImpl(db, versionManager, migrator, scriptRepo.latestToVersion)
    }

    def dbFacadeFactory(): ArangoDatabaseFacade =
      new ArangoDatabaseFacade(connectionURL)

    new AutoClosingArangoManagerProxy(dbManagerFactory, dbFacadeFactory)
  }

}
